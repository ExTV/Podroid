/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * podroid-hostd - guest side of the Android host bridge.
 *
 * Multi-call binary (argv[0] basename):
 *   podroid-hostd     daemon: relays /run/podroid-host.sock <-> Android.
 *   podroid-notify    CLI: post an Android notification.
 *   podroid-forward   CLI: add/remove/list Android port forwards.
 *
 * Daemon transport (one request line -> one response line, serialized):
 *   AVF  (podroid.backend=avf in /proc/cmdline): listen AF_VSOCK :9101, accept
 *        the Android connection.
 *   QEMU (otherwise): open /dev/hvc2.
 */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <libgen.h>
#include <linux/vm_sockets.h>
#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/un.h>
#include <termios.h>
#include <unistd.h>

#define SOCK_PATH   "/run/podroid-host.sock"
#define HVC_PATH    "/dev/hvc2"
#define VSOCK_PORT  9101
#define HOST_TIMEOUT_S 5

/* base64 (standard alphabet, no wrap) */
static const char B64[] =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

static char *b64encode(const unsigned char *in, size_t len) {
    size_t olen = 4 * ((len + 2) / 3);
    char *out = malloc(olen + 1);
    if (!out) return NULL;
    size_t i, o = 0;
    for (i = 0; i + 2 < len; i += 3) {
        out[o++] = B64[in[i] >> 2];
        out[o++] = B64[((in[i] & 3) << 4) | (in[i+1] >> 4)];
        out[o++] = B64[((in[i+1] & 15) << 2) | (in[i+2] >> 6)];
        out[o++] = B64[in[i+2] & 63];
    }
    if (i < len) {
        out[o++] = B64[in[i] >> 2];
        if (i + 1 < len) {
            out[o++] = B64[((in[i] & 3) << 4) | (in[i+1] >> 4)];
            out[o++] = B64[(in[i+1] & 15) << 2];
        } else {
            out[o++] = B64[(in[i] & 3) << 4];
            out[o++] = '=';
        }
        out[o++] = '=';
    }
    out[o] = '\0';
    return out;
}

static int b64val(char c) {
    if (c >= 'A' && c <= 'Z') return c - 'A';
    if (c >= 'a' && c <= 'z') return c - 'a' + 26;
    if (c >= '0' && c <= '9') return c - '0' + 52;
    if (c == '+') return 62;
    if (c == '/') return 63;
    return -1;
}

/* Decodes base64 into a freshly malloc'd NUL-terminated buffer. NULL on error. */
static char *b64decode(const char *in) {
    size_t len = strlen(in);
    char *out = malloc(len / 4 * 3 + 4);
    if (!out) return NULL;
    size_t o = 0;
    int acc = 0, nbits = 0;
    for (size_t i = 0; i < len; i++) {
        if (in[i] == '=') break;
        int v = b64val(in[i]);
        if (v < 0) { free(out); return NULL; }
        acc = (acc << 6) | v; nbits += 6;
        if (nbits >= 8) { nbits -= 8; out[o++] = (char)((acc >> nbits) & 0xFF); }
    }
    out[o] = '\0';
    return out;
}

static int write_all(int fd, const char *buf, size_t len) {
    while (len) {
        ssize_t w = write(fd, buf, len);
        if (w < 0) { if (errno == EINTR) continue; return -1; }
        buf += w; len -= (size_t)w;
    }
    return 0;
}

static int write_line(int fd, const char *s) {
    if (write_all(fd, s, strlen(s)) < 0) return -1;
    return write_all(fd, "\n", 1);
}

/* Reads one LF-terminated line into buf (NUL-terminated, LF stripped).
 * Returns line length, 0 on EOF, -1 on error/timeout. */
static int read_line(int fd, char *buf, size_t cap) {
    size_t n = 0;
    while (n < cap - 1) {
        char c;
        ssize_t r = read(fd, &c, 1);
        if (r < 0) { if (errno == EINTR) continue; return -1; }
        if (r == 0) return n == 0 ? 0 : (int)n;
        if (c == '\n') break;
        buf[n++] = c;
    }
    buf[n] = '\0';
    return (int)n;
}

/* Like read_line but waits at most timeout_s for activity before each byte;
 * returns line length, 0 on EOF, -1 on error/timeout. poll() works on both
 * char devices (/dev/hvc2) and sockets, unlike SO_RCVTIMEO. */
static int read_line_timeout(int fd, char *buf, size_t cap, int timeout_s) {
    size_t n = 0;
    while (n < cap - 1) {
        struct pollfd pfd = { .fd = fd, .events = POLLIN };
        int pr = poll(&pfd, 1, timeout_s * 1000);
        if (pr < 0) { if (errno == EINTR) continue; return -1; }
        if (pr == 0) return -1; /* timeout */
        char c;
        ssize_t r = read(fd, &c, 1);
        if (r < 0) { if (errno == EINTR) continue; return -1; }
        if (r == 0) return n == 0 ? 0 : (int)n;
        if (c == '\n') break;
        buf[n++] = c;
    }
    buf[n] = '\0';
    return (int)n;
}

/* Discard any bytes already readable on the host channel before we send a new
 * request. Android answers exactly one response per request, so anything
 * pending here is stale - an orphaned response from an exchange that was
 * abandoned on timeout, or buffered across a daemon restart / device reopen.
 * Draining it keeps the channel self-synchronising: the response we read after
 * write_line() then always corresponds to the request we just sent, instead of
 * the stream drifting one message behind permanently. */
static void drain_pending(int fd) {
    int fl = fcntl(fd, F_GETFL, 0);
    if (fl < 0) return;
    if (fcntl(fd, F_SETFL, fl | O_NONBLOCK) < 0) return;
    char buf[4096];
    while (read(fd, buf, sizeof(buf)) > 0) { /* discard stale bytes */ }
    fcntl(fd, F_SETFL, fl);  /* restore blocking */
}

/* Connects to the daemon, sends `req`, reads one response line into resp. */
static int cli_roundtrip(const char *req, char *resp, size_t cap) {
    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) return -1;
    struct sockaddr_un sa = {0};
    sa.sun_family = AF_UNIX;
    strncpy(sa.sun_path, SOCK_PATH, sizeof(sa.sun_path) - 1);
    if (connect(fd, (struct sockaddr *)&sa, sizeof(sa)) < 0) { close(fd); return -1; }
    if (write_line(fd, req) < 0) { close(fd); return -1; }
    int n = read_line(fd, resp, cap);
    close(fd);
    return n <= 0 ? -1 : 0;
}

static int cli_report(const char *resp, int list_decode) {
    if (strncmp(resp, "ERR ", 4) == 0) {
        char *msg = b64decode(resp + 4);
        fprintf(stderr, "podroid: %s\n", msg ? msg : "error");
        free(msg);
        return 1;
    }
    if (strcmp(resp, "OK") == 0) return 0;
    if (strncmp(resp, "OK ", 3) == 0) {
        if (list_decode) {
            char *t = b64decode(resp + 3);
            if (t && *t) printf("%s\n", t);
            free(t);
        } else {
            printf("%s\n", resp + 3);
        }
        return 0;
    }
    if (strcmp(resp, "PONG") == 0) { printf("PONG\n"); return 0; }
    fprintf(stderr, "podroid: unexpected response: %s\n", resp);
    return 1;
}

static int cli_notify(int argc, char **argv) {
    const char *title = NULL, *prio = "normal", *id = "-";
    int i = 1;
    for (; i < argc; i++) {
        if (strcmp(argv[i], "--title") == 0 && i + 1 < argc) title = argv[++i];
        else if (strcmp(argv[i], "--priority") == 0 && i + 1 < argc) prio = argv[++i];
        else if (strcmp(argv[i], "--id") == 0 && i + 1 < argc) id = argv[++i];
        else break;
    }
    if (i >= argc) { fprintf(stderr, "usage: podroid-notify [--title T] [--priority low|normal|high] [--id N] BODY\n"); return 2; }
    size_t blen = 0;
    for (int j = i; j < argc; j++) blen += strlen(argv[j]) + 1;
    char *body = malloc(blen + 1); body[0] = '\0';
    for (int j = i; j < argc; j++) { if (j > i) strcat(body, " "); strcat(body, argv[j]); }

    char *b64title = title ? b64encode((const unsigned char *)title, strlen(title)) : NULL;
    char *b64body = b64encode((const unsigned char *)body, strlen(body));
    char req[8192];
    snprintf(req, sizeof(req), "NOTIFY %s %s %s %s",
             prio, id, b64title ? b64title : "-", b64body);
    free(body); free(b64title); free(b64body);

    char resp[8192];
    if (cli_roundtrip(req, resp, sizeof(resp)) < 0) {
        fprintf(stderr, "podroid: host bridge not available\n"); return 1;
    }
    return cli_report(resp, 0);
}

static int cli_forward(int argc, char **argv) {
    char req[256];
    int list_decode = 0;
    if (argc >= 3 && argv[1][0] >= '0' && argv[1][0] <= '9') {
        snprintf(req, sizeof(req), "FWD-ADD %s %s tcp", argv[1], argv[2]);
    } else if (argc >= 4 && strcmp(argv[1], "add") == 0) {
        const char *proto = (argc >= 5) ? argv[4] : "tcp";
        snprintf(req, sizeof(req), "FWD-ADD %s %s %s", argv[2], argv[3], proto);
    } else if (argc >= 3 && strcmp(argv[1], "remove") == 0) {
        const char *proto = (argc >= 4) ? argv[3] : "tcp";
        snprintf(req, sizeof(req), "FWD-REMOVE %s %s", argv[2], proto);
    } else if (argc >= 2 && strcmp(argv[1], "list") == 0) {
        snprintf(req, sizeof(req), "FWD-LIST");
        list_decode = 1;
    } else {
        fprintf(stderr,
            "usage:\n"
            "  podroid-forward <hostPort> <guestPort>\n"
            "  podroid-forward add <hostPort> <guestPort> [tcp|udp]\n"
            "  podroid-forward remove <hostPort> [tcp|udp]\n"
            "  podroid-forward list\n");
        return 2;
    }
    char resp[8192];
    if (cli_roundtrip(req, resp, sizeof(resp)) < 0) {
        fprintf(stderr, "podroid: host bridge not available\n"); return 1;
    }
    return cli_report(resp, list_decode);
}

/* On QEMU the host channel is /dev/hvc2, a virtio-console TTY that defaults to
 * cooked mode with ECHO on. Echo is fatal here: every response the guest reads
 * gets echoed straight back out to Android, which then parses its own "OK ..."
 * as the next request and the stream desyncs permanently after the first
 * exchange. Put the fd in raw mode so the channel is a clean byte pipe. No-op on
 * AVF, where the host channel is a vsock socket (isatty is false). */
static void set_raw_if_tty(int fd) {
    if (!isatty(fd)) return;
    struct termios tio;
    if (tcgetattr(fd, &tio) != 0) return;
    cfmakeraw(&tio);
    tcsetattr(fd, TCSANOW, &tio);
}

static int is_avf(void) {
    int fd = open("/proc/cmdline", O_RDONLY);
    if (fd < 0) return 0;
    char buf[4096]; ssize_t n = read(fd, buf, sizeof(buf) - 1); close(fd);
    if (n <= 0) return 0;
    buf[n] = '\0';
    return strstr(buf, "podroid.backend=avf") != NULL;
}

static int make_unix_listener(void) {
    unlink(SOCK_PATH);
    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) return -1;
    struct sockaddr_un sa = {0};
    sa.sun_family = AF_UNIX;
    strncpy(sa.sun_path, SOCK_PATH, sizeof(sa.sun_path) - 1);
    if (bind(fd, (struct sockaddr *)&sa, sizeof(sa)) < 0) { close(fd); return -1; }
    chmod(SOCK_PATH, 0666);
    if (listen(fd, 16) < 0) { close(fd); return -1; }
    return fd;
}

static int make_vsock_listener(void) {
    int fd = socket(AF_VSOCK, SOCK_STREAM, 0);
    if (fd < 0) return -1;
    struct sockaddr_vm sa = {0};
    sa.svm_family = AF_VSOCK;
    sa.svm_cid = VMADDR_CID_ANY;
    sa.svm_port = VSOCK_PORT;
    if (bind(fd, (struct sockaddr *)&sa, sizeof(sa)) < 0) { close(fd); return -1; }
    if (listen(fd, 4) < 0) { close(fd); return -1; }
    return fd;
}

static int daemon_main(void) {
    signal(SIGPIPE, SIG_IGN);
    int avf = is_avf();
    int cli_listener = make_unix_listener();
    if (cli_listener < 0) { perror("podroid-hostd: unix listener"); return 1; }

    int vsock_listener = -1;
    if (avf) {
        vsock_listener = make_vsock_listener();
        if (vsock_listener < 0) { perror("podroid-hostd: vsock listener"); return 1; }
    }

    int host_fd = -1;
    for (;;) {
        int cli = accept(cli_listener, NULL, NULL);
        if (cli < 0) { if (errno == EINTR) continue; break; }

        char req[8192];
        int rn = read_line(cli, req, sizeof(req));
        if (rn <= 0) { close(cli); continue; }

        if (host_fd < 0) {
            if (avf) {
                host_fd = accept(vsock_listener, NULL, NULL);
            } else {
                host_fd = open(HVC_PATH, O_RDWR | O_NOCTTY);
            }
            if (host_fd >= 0) set_raw_if_tty(host_fd);
        }
        if (host_fd < 0) { write_line(cli, "ERR aG9zdCBjaGFubmVsIG5vdCBjb25uZWN0ZWQ="); close(cli); continue; }

        char resp[8192];
        drain_pending(host_fd);  /* clear any stale/orphaned bytes so resp matches this req */
        if (write_line(host_fd, req) < 0 ||
            read_line_timeout(host_fd, resp, sizeof(resp), HOST_TIMEOUT_S) <= 0) {
            close(host_fd); host_fd = -1;
            write_line(cli, "ERR dGltZW91dA==");
            close(cli);
            continue;
        }
        write_line(cli, resp);
        close(cli);
    }
    return 0;
}

int main(int argc, char **argv) {
    char *base = basename(argv[0]);
    if (strcmp(base, "podroid-notify") == 0) return cli_notify(argc, argv);
    if (strcmp(base, "podroid-forward") == 0) return cli_forward(argc, argv);
    return daemon_main();
}
