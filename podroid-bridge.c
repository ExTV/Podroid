/*
 * podroid-bridge — Serial socket terminal bridge for Podroid
 *
 * Relays data between:
 *   stdin/stdout  (TerminalSession PTY, managed by Termux)
 *   serial.sock   (QEMU unix socket serial — ttyAMA0 in the VM)
 *
 * On SIGWINCH (triggered by TerminalSession.updateSize → ioctl TIOCSWINSZ):
 *   reads the new PTY dimensions and sends "RESIZE rows cols\n" to ctrl.sock.
 *   The VM daemon reads from /dev/hvc0 and calls stty on ttyAMA0 — the Linux
 *   kernel automatically sends SIGWINCH to the foreground process group.
 *
 * argv[1] = path to serial.sock   (QEMU serial unix socket)
 * argv[2] = path to ctrl.sock     (QEMU virtio-console ctrl chardev socket)
 *
 * Built for Android ARM64 with 16KB page alignment.
 * Shipped as libpodroid-bridge.so so Android extracts it from the APK.
 */

#include <errno.h>
#include <signal.h>
#include <stdio.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/select.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <termios.h>
#include <unistd.h>

static volatile sig_atomic_t g_winch = 0;
static int g_ctrl_fd = -1;

static void on_winch(int sig) {
    (void)sig;
    g_winch = 1;
}

static int connect_unix(const char *path) {
    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) return -1;
    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    strncpy(addr.sun_path, path, sizeof(addr.sun_path) - 1);
    if (connect(fd, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
        close(fd);
        return -1;
    }
    return fd;
}

static void send_resize(void) {
    if (g_ctrl_fd < 0) return;
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    if (ioctl(STDIN_FILENO, TIOCGWINSZ, &ws) < 0) return;
    if (ws.ws_row == 0 || ws.ws_col == 0) return;
    char msg[64];
    int n = snprintf(msg, sizeof(msg), "RESIZE %d %d\n", ws.ws_row, ws.ws_col);
    if (n > 0) {
        /* Ignore write errors — ctrl is best-effort */
        (void)write(g_ctrl_fd, msg, n);
    }
}

static int write_all(int fd, const char *buf, int n) {
    int written = 0;
    while (written < n) {
        int w = write(fd, buf + written, n - written);
        if (w <= 0) return -1;
        written += w;
    }
    return 0;
}

/*
 * filter_csi6n — strip CSI 6n responses from VM data.
 *
 * CSI 6n is the emulator's response to the shell's DA1 query (\033[6n).
 * The response format is \033[<row>;<col>R, e.g. \033[16;5R.
 *
 * When this response reaches ash via the PTY, ash reads it as keyboard input
 * and prints "^[[16;5R" garbage. Filtering it here prevents that.
 *
 * CSI 6n is the ONLY response we filter — all other CSI sequences pass
 * through normally (mouse tracking, title changes, etc.).
 */
static int filter_csi6n(char *buf, int n) {
    char *src = buf;
    char *dst = buf;
    int i = 0;
    while (i < n) {
        if (i < n - 2 && buf[i] == '\033' && buf[i + 1] == '[') {
            /* Check for CSI 6n: ESC [ <digits> ; <digits> R */
            int j = i + 2;
            while (j < n && buf[j] >= '0' && buf[j] <= '9') j++;
            if (j < n && buf[j] == ';') {
                int k = j + 1;
                while (k < n && buf[k] >= '0' && buf[k] <= '9') k++;
                if (k < n && buf[k] == 'R') {
                    /* Matched CSI 6n — skip it */
                    i = k + 1;
                    continue;
                }
            }
        }
        *dst++ = buf[i++];
    }
    return dst - buf;
}

int main(int argc, char *argv[]) {
    if (argc < 3) {
        fprintf(stderr, "Usage: %s <serial.sock> <ctrl.sock>\n", argv[0]);
        return 1;
    }

    /* Retry serial connection — QEMU only serves one client at a time on the
     * unix socket, so the boot monitor may still be connected briefly after
     * releaseSerial() on the Android side. */
    int serial_fd = -1;
    for (int attempt = 0; attempt < 50; attempt++) {   /* up to 10 seconds */
        serial_fd = connect_unix(argv[1]);
        if (serial_fd >= 0) break;
        usleep(200000);                                 /* 200ms */
    }
    if (serial_fd < 0) {
        fprintf(stderr, "podroid-bridge: cannot connect to %s after retries: %s\n",
                argv[1], strerror(errno));
        return 1;
    }

    /* Put the local PTY into raw mode — the VM's ttyAMA0 handles echo,
     * line editing, and signal generation. Without this, both the local PTY
     * and the remote terminal echo, causing every keystroke to appear twice. */
    {
        struct termios raw;
        if (tcgetattr(STDIN_FILENO, &raw) == 0) {
            cfmakeraw(&raw);
            tcsetattr(STDIN_FILENO, TCSANOW, &raw);
        }
    }

    /* ctrl socket is optional — resize won't propagate if unavailable */
    g_ctrl_fd = connect_unix(argv[2]);

    /* Send initial terminal size right away so the VM has it from first keypress */
    send_resize();

    signal(SIGWINCH, on_winch);

    char buf[4096];
    for (;;) {
        if (g_winch) {
            g_winch = 0;
            send_resize();
        }

        fd_set rfds;
        FD_ZERO(&rfds);
        FD_SET(STDIN_FILENO, &rfds);
        FD_SET(serial_fd, &rfds);
        int nfds = serial_fd + 1;

        /*
         * 100ms timeout so we re-check g_winch even when there is no I/O.
         * Keeps resize responsive without busy-looping.
         */
        struct timeval tv = { .tv_sec = 0, .tv_usec = 100000 };
        int ret = select(nfds, &rfds, NULL, NULL, &tv);
        if (ret < 0) {
            if (errno == EINTR) continue;
            break;
        }

        /* Keyboard → serial */
        if (FD_ISSET(STDIN_FILENO, &rfds)) {
            int n = read(STDIN_FILENO, buf, sizeof(buf));
            if (n <= 0) break;
            if (write_all(serial_fd, buf, n) < 0) break;
        }

        /* Serial → screen (filter CSI 6n garbage before forwarding to PTY) */
        if (FD_ISSET(serial_fd, &rfds)) {
            int n = read(serial_fd, buf, sizeof(buf));
            if (n <= 0) break;
            int filtered = filter_csi6n(buf, n);
            if (write_all(STDOUT_FILENO, buf, filtered) < 0) break;
        }
    }

    close(serial_fd);
    if (g_ctrl_fd >= 0) close(g_ctrl_fd);
    return 0;
}
