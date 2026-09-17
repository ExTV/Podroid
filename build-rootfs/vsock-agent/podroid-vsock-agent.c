/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * podroid-vsock-agent — guest-side peer for Podroid's AVF backend.
 *
 * Reads /etc/podroid/forwards.conf at startup. Each line is one of:
 *
 *   <vport> tcp <host> <gport>     listen on AF_VSOCK port vport, splice each
 *                                  accepted connection to TCP host:gport
 *   <vport> udp <host> <gport>     listen on AF_VSOCK port vport; each accepted
 *                                  connection carries length-framed datagrams
 *                                  ([u16 BE len][payload]) relayed to UDP host:gport
 *   <vport> ctl                    listen on AF_VSOCK port vport, accept ONE
 *                                  control connection at a time, handle the
 *                                  line-oriented protocol below
 *
 * Control protocol (LF-terminated ASCII):
 *
 *   RESIZE <rows> <cols>           stty rows/cols on /dev/ttyS0, also persist
 *                                  to /run/term_size for podroid-login restore
 *   ADD    <vport> <tcp|udp> <host> <gp> fork new listener immediately
 *                                  (idempotent on vport; replaces the
 *                                  listener in place if the target changed)
 *   REMOVE <vport>                 kill listener child for vport
 *   PING                           reply "PONG\n"
 *
 * forwards.conf is never rewritten by ADD/REMOVE: it is read once at startup
 * as the initial seed, and Android replays every live forward over the ctl
 * channel on each boot (AvfEngine bring-up), so the file needs no persisted
 * runtime state.
 *
 * Listener children are tracked by {vport, pid, target} in a small in-memory
 * table so REMOVE can SIGTERM the correct child and a re-ADD with a changed
 * target replaces it instead of no-op'ing. The control loop runs in the
 * parent so the table is the parent's local state; no IPC needed.
 *
 * All TCP listeners bind to AF_VSOCK with CID = VMADDR_CID_ANY (host can reach
 * us regardless of the assigned CID).
 *
 * Invoked as `podroid-vsock-agent downloads-9p` (argv[1] dispatch, separate
 * from the config-driven modes above): runs only the Downloads share's 9p
 * rendezvous listener (see downloads_9p_serve()) instead of the forwards.conf
 * + ctl loop.
 */

#define _GNU_SOURCE
#include <arpa/inet.h>
#include <ctype.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/vm_sockets.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <poll.h>
#include <signal.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mount.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <termios.h>
#include <sys/ioctl.h>
#include <unistd.h>

#define CONFIG_PATH     "/etc/podroid/forwards.conf"
#define TERM_SIZE_PATH  "/run/term_size"
#define LOG_TAG         "podroid-vsock-agent"

/* Reserved vsock port for the AVF Downloads share's 9p rendezvous. Keep this
 * equal to AvfDownloadsShare.DOWNLOADS_VSOCK_PORT in the Android code. */
#define PODROID_DOWNLOADS_VSOCK_PORT 200000
#define PODROID_DOWNLOADS_MOUNT      "/mnt/downloads"

/* ── Logging ─────────────────────────────────────────────────────────────── */

/*
 * Single-syscall log. fprintf+vfprintf+fprintf+fflush issues 3–4 write()s
 * which under fork() interleave at the byte level across child processes
 * (we saw "podroid-vsock-agent [error] podroid-vsock-agent [error] ctl
 * socket() failed: ...vsock socket() failed: ..." mixed in /var/log).
 * write(2, buf, n) is atomic for n < PIPE_BUF (4096) on Linux for regular
 * files and pipes — formatting the whole line into a stack buffer then
 * issuing one write() guarantees ordering even with many forked listeners.
 */
static void logmsg(const char *level, const char *fmt, ...) {
    char buf[1024];
    int n = snprintf(buf, sizeof(buf), "%s [%s] ", LOG_TAG, level);
    if (n < 0 || n >= (int)sizeof(buf) - 2) return;
    va_list ap;
    va_start(ap, fmt);
    int m = vsnprintf(buf + n, sizeof(buf) - n - 2, fmt, ap);
    va_end(ap);
    if (m < 0) return;
    int total = n + m;
    if (total > (int)sizeof(buf) - 2) total = sizeof(buf) - 2;
    buf[total]     = '\n';
    buf[total + 1] = '\0';
    (void)write(2, buf, (size_t)(total + 1));
}

#define LOG_I(...) logmsg("info",  __VA_ARGS__)
#define LOG_W(...) logmsg("warn",  __VA_ARGS__)
#define LOG_E(...) logmsg("error", __VA_ARGS__)

/* Length-framed UDP-over-vsock: [u16 BE len][payload]. Matches DatagramFraming.kt. */
#define UDP_FRAME_MAX     65535
#define UDP_IDLE_BACKSTOP 90   /* seconds of total silence before a relay child self-exits */

/* ── Listener table ─────────────────────────────────────────────────────── */

#define MAX_LISTENERS 64
struct listener { int vport; pid_t pid; int is_udp; int gport; char host[64]; };
static struct listener listeners[MAX_LISTENERS];
static int listener_count = 0;

static int listener_add(int vport, pid_t pid, int is_udp, const char *host, int gport) {
    if (listener_count >= MAX_LISTENERS) return -1;
    listeners[listener_count].vport = vport;
    listeners[listener_count].pid = pid;
    listeners[listener_count].is_udp = is_udp;
    listeners[listener_count].gport = gport;
    snprintf(listeners[listener_count].host, sizeof(listeners[listener_count].host), "%s", host);
    listener_count++;
    return 0;
}

static int listener_find(int vport) {
    for (int i = 0; i < listener_count; i++)
        if (listeners[i].vport == vport) return i;
    return -1;
}

static void listener_remove(int idx) {
    if (idx < 0 || idx >= listener_count) return;
    listeners[idx] = listeners[listener_count - 1];
    listener_count--;
}

/*
 * Lazy liveness check for a tracked listener pid. reap_children() must stay
 * async-signal-safe, so it can't prune listeners[] from the SIGCHLD handler;
 * instead the main-loop functions validate a row before trusting it. A dead
 * listener (post-fork bind/listen failure → _exit, or external kill) leaves a
 * stale {vport,pid} row whose pid the kernel may have recycled for an
 * unrelated process group — kill(-pid) on it would signal the wrong group, and
 * a re-ADD would no-op on the stale row. kill(pid,0) probes existence without
 * sending a signal; ESRCH means the pid is gone (or never ours after recycle).
 *
 * Residual TOCTOU: the pid could in theory be reaped and recycled between this
 * check and a subsequent kill(). But our own children are only reaped here in
 * the single-threaded main loop after this returns alive, so within one
 * handler the result holds; this is vastly safer than blindly trusting a row
 * that has been stale across many event-loop iterations. */
static int pid_alive(pid_t pid) {
    return kill(pid, 0) == 0 || errno != ESRCH;
}

/* Create + bind + listen an AF_VSOCK stream socket on vport. Returns the fd, or
 * -1 (logged) on failure. Shared by the tcp and udp listeners. */
static int vsock_listen(int vport) {
    int s = socket(AF_VSOCK, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (s < 0) { LOG_E("vsock socket() failed: %s", strerror(errno)); return -1; }
    int one = 1;
    setsockopt(s, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
    struct sockaddr_vm sa;
    memset(&sa, 0, sizeof(sa));
    sa.svm_family = AF_VSOCK;
    sa.svm_cid    = VMADDR_CID_ANY;
    sa.svm_port   = (unsigned int)vport;
    if (bind(s, (struct sockaddr *)&sa, sizeof(sa)) < 0) {
        LOG_E("vsock bind(%d) failed: %s", vport, strerror(errno)); close(s); return -1;
    }
    if (listen(s, 16) < 0) {
        LOG_E("vsock listen(%d) failed: %s", vport, strerror(errno)); close(s); return -1;
    }
    return s;
}

/* Write all n bytes to a (non-blocking) fd, waiting for writability on EAGAIN.
 * Returns 0 on success, -1 on hard error. */
static int write_all(int fd, const unsigned char *buf, size_t n) {
    size_t off = 0;
    while (off < n) {
        ssize_t w = write(fd, buf + off, n - off);
        if (w > 0) { off += (size_t)w; continue; }
        if (w < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
            fd_set wf; FD_ZERO(&wf); FD_SET(fd, &wf);
            if (select(fd + 1, NULL, &wf, NULL, NULL) < 0) {
                if (errno == EINTR) continue;
                return -1;
            }
            continue;
        }
        if (w < 0 && errno == EINTR) continue;
        return -1;
    }
    return 0;
}

/* ── Splice loop (TCP listener child) ───────────────────────────────────── */

/*
 * Close every inherited descriptor except std{in,out,err} and `keep`, so a
 * forked splice child holds only its connection fd. We don't exec (CLOEXEC
 * alone wouldn't drop these), so close them explicitly. getdtablesize() bounds
 * the scan to the actual fd-table size; FD_CLOEXEC on every socket keeps the
 * worst case small. close() errors on never-opened fds are ignored.
 */
static void close_inherited_fds(int keep) {
    int maxfd = (int)getdtablesize();
    if (maxfd < 0 || maxfd > 4096) maxfd = 4096;  /* sane cap */
    for (int fd = 3; fd < maxfd; fd++) {
        if (fd == keep) continue;
        close(fd);
    }
}

static int set_nonblock(int fd) {
    int fl = fcntl(fd, F_GETFL, 0);
    if (fl < 0) return -1;
    return fcntl(fd, F_SETFL, fl | O_NONBLOCK);
}

/*
 * One relay direction: bytes flow src → dst through a single in-flight buffer.
 * `len` is bytes buffered, `off` how many already written; src_eof latches when
 * src returns 0/error. The destination's write side is half-closed (SHUT_WR)
 * exactly once, after src_eof AND the buffer has fully drained, so the peer
 * sees EOF without losing the last bytes.
 */
struct relay_dir {
    int src, dst;
    char buf[16384];
    size_t len, off;
    int src_eof;
    int wr_shut;
};

/*
 * Full-duplex byte relay between two sockets. Unlike the bridge (which forwards
 * an interactive PTY whose consumer drains continuously, so a one-direction
 * blocking write is fine), this agent forwards arbitrary TCP — including bulk
 * transfers in BOTH directions over one connection (e.g. SCP reading and
 * writing at once). A read-only select() plus a shared-buffer blocking write
 * would deadlock there: both peers' send buffers fill, both blocking writes
 * stall, and neither side ever drains. So both fds go non-blocking, each
 * direction keeps its own pending buffer, and we select() for writability
 * before writing and for readability only when that direction's buffer is
 * empty. A stalled write in one direction can no longer starve reads in the
 * other. The SHUT_WR-on-EOF half-close is preserved per direction.
 */
static void splice_loop(int a, int b) {
    fd_set rfds, wfds;
    /* select() can't represent fds >= FD_SETSIZE without scribbling past the
     * bitmap. FD_CLOEXEC on every socket keeps fd numbers low, but bail safely
     * rather than corrupt the stack if the table ever climbs that high. */
    if (a >= FD_SETSIZE || b >= FD_SETSIZE) {
        close(a);
        close(b);
        return;
    }
    /* Non-blocking is what makes select-on-write meaningful: a write that would
     * block returns EAGAIN instead of stalling the whole relay. */
    set_nonblock(a);
    set_nonblock(b);
    int maxfd = (a > b ? a : b) + 1;

    struct relay_dir ab = { .src = a, .dst = b, .len = 0, .off = 0, .src_eof = 0, .wr_shut = 0 };
    struct relay_dir ba = { .src = b, .dst = a, .len = 0, .off = 0, .src_eof = 0, .wr_shut = 0 };
    struct relay_dir *dirs[2] = { &ab, &ba };

    for (;;) {
        FD_ZERO(&rfds);
        FD_ZERO(&wfds);
        int active = 0;
        for (int i = 0; i < 2; i++) {
            struct relay_dir *d = dirs[i];
            /* Read more only when the buffer is empty and the source is open. */
            if (!d->src_eof && d->len == d->off) { FD_SET(d->src, &rfds); active = 1; }
            /* Write only when there are buffered bytes pending. */
            if (d->len > d->off) { FD_SET(d->dst, &wfds); active = 1; }
        }
        if (!active) break;  /* both directions drained and EOF'd */

        if (select(maxfd, &rfds, &wfds, NULL, NULL) < 0) {
            if (errno == EINTR) continue;
            break;
        }

        int fatal = 0;
        for (int i = 0; i < 2 && !fatal; i++) {
            struct relay_dir *d = dirs[i];
            /* Flush pending bytes first so the buffer frees up for the next read. */
            if (d->len > d->off && FD_ISSET(d->dst, &wfds)) {
                ssize_t w = write(d->dst, d->buf + d->off, d->len - d->off);
                if (w < 0) {
                    if (errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR) { fatal = 1; break; }
                } else if (w == 0) {
                    fatal = 1; break;
                } else {
                    d->off += (size_t)w;
                    if (d->off == d->len) d->len = d->off = 0;
                }
            }
            /* Refill the buffer from the source when it's empty. */
            if (!d->src_eof && d->len == d->off && FD_ISSET(d->src, &rfds)) {
                ssize_t n = read(d->src, d->buf, sizeof(d->buf));
                if (n > 0) {
                    d->len = (size_t)n;
                    d->off = 0;
                } else if (n == 0) {
                    d->src_eof = 1;
                } else if (errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR) {
                    d->src_eof = 1;  /* treat a hard read error as EOF for this side */
                }
            }
            /* Once the source is done and its buffer is drained, half-close the
             * destination's write side so the peer sees EOF (done once). */
            if (d->src_eof && d->len == d->off && !d->wr_shut) {
                shutdown(d->dst, SHUT_WR);
                d->wr_shut = 1;
            }
        }
        if (fatal) break;
    }
    close(a);
    close(b);
}

static int tcp_connect(const char *host, int port) {
    /* SOCK_CLOEXEC: this fd is the splice child's connection fd. It is dup'd
     * onto nothing and never exec'd, but CLOEXEC keeps it from leaking should
     * an exec ever be added, and matches every other socket here. */
    int s = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (s < 0) return -1;
    struct sockaddr_in sa;
    memset(&sa, 0, sizeof(sa));
    sa.sin_family = AF_INET;
    sa.sin_port = htons((uint16_t)port);
    if (inet_pton(AF_INET, host, &sa.sin_addr) != 1) {
        close(s); errno = EINVAL; return -1;
    }
    if (connect(s, (struct sockaddr *)&sa, sizeof(sa)) < 0) {
        close(s); return -1;
    }
    /* Interactive forwards (SSH, VNC) get Nagle-delayed without this. */
    int one = 1;
    setsockopt(s, IPPROTO_TCP, TCP_NODELAY, &one, sizeof(one));
    return s;
}

/* Forks the parent off — this function never returns in the child. sync_fd is
 * the write end of a pipe the parent reads to learn whether bind/listen
 * succeeded before it advertises this forward. */
static void tcp_listener_main(int vport, const char *host, int gport, int sync_fd) {
    /* New process group rooted at this listener's PID. Splice children forked
     * below inherit it, so REMOVE can kill(-pgid) the listener AND every
     * in-flight connection in one shot. setpgid(0,0) makes pgid == our pid. */
    setpgid(0, 0);
    int s = vsock_listen(vport);
    /* Report the bind/listen result to the parent before it records the
     * {vport,pid} row: a failure (e.g. EADDRINUSE in a fast remove-then-add)
     * must not advertise a listener that isn't actually accepting. */
    char ok = (s < 0) ? 0 : 1;
    if (write(sync_fd, &ok, 1) != 1) { /* parent gone; nothing to do */ }
    close(sync_fd);
    if (s < 0) _exit(1);
    /* Drop fds inherited from the parent that this listener has no use for: the
     * ctl listening socket, and (when forked from a live handle_add) the ctl
     * connection fd plus its dup in ctl_loop, as well as sibling forwarders'
     * listening sockets. They're CLOEXEC but this process never exec's, so
     * without an explicit close they stay pinned for the listener's whole life
     * — keeping a removed ctl/forward fd alive. Our own `s` is the only socket
     * to keep; splice children re-run close_inherited_fds(c) for their fd. */
    close_inherited_fds(s);
    LOG_I("tcp listener: vsock:%d → %s:%d", vport, host, gport);

    for (;;) {
        /* accept4(SOCK_CLOEXEC): the connection fd `c` shouldn't leak into a
         * future exec; it stays usable for splice_loop in this no-exec path. */
        int c = accept4(s, NULL, NULL, SOCK_CLOEXEC);
        if (c < 0) { if (errno == EINTR) continue; break; }
        pid_t child = fork();
        if (child < 0) { close(c); continue; }
        if (child == 0) {
            /* Drop every inherited fd except the accepted connection `c`: this
             * listener's own `s`, the ctl listening socket, sibling
             * forwarders' listeners, and (if forked from a live handle_add)
             * the ctl connection + its dup. Without this they stay pinned for
             * the connection's whole lifetime, blocking re-ADD of a removed
             * vport with EADDRINUSE. tcp_connect()'s `t` is opened after. */
            close_inherited_fds(c);
            int t = tcp_connect(host, gport);
            if (t < 0) { LOG_W("tcp connect %s:%d failed: %s", host, gport, strerror(errno)); _exit(1); }
            splice_loop(c, t);
            _exit(0);
        }
        close(c);
    }
    _exit(0);
}

/* ── UDP relay (udp listener child) ─────────────────────────────────────── */

/*
 * One vsock stream connection <-> one guest UDP socket. The vsock carries
 * length-framed datagrams ([u16 BE len][payload]); the UDP socket is connect()ed
 * to host:gport so we use send()/recv() and the kernel filters replies to that
 * peer. Exits on vsock EOF (Android reaped the flow) or after UDP_IDLE_BACKSTOP
 * seconds of silence (backstop in case Android dies without closing).
 */
static void udp_relay(int vsock_fd, const char *host, int gport) {
    int u = socket(AF_INET, SOCK_DGRAM | SOCK_CLOEXEC, 0);
    if (u < 0) { LOG_W("udp socket() failed: %s", strerror(errno)); close(vsock_fd); return; }
    struct sockaddr_in sa;
    memset(&sa, 0, sizeof(sa));
    sa.sin_family = AF_INET;
    sa.sin_port = htons((uint16_t)gport);
    if (inet_pton(AF_INET, host, &sa.sin_addr) != 1) {
        LOG_W("udp relay: bad host address '%s'", host);
        close(u); close(vsock_fd); return;
    }
    if (connect(u, (struct sockaddr *)&sa, sizeof(sa)) < 0) {
        LOG_W("udp connect %s:%d failed: %s", host, gport, strerror(errno));
        close(u); close(vsock_fd); return;
    }
    if (vsock_fd >= FD_SETSIZE || u >= FD_SETSIZE) { close(u); close(vsock_fd); return; }
    set_nonblock(vsock_fd);
    set_nonblock(u);
    int maxfd = (vsock_fd > u ? vsock_fd : u) + 1;

    /* Per-call locals (this child runs udp_relay once then _exit). acc holds the
     * vsock->udp frame-reassembly bytes; ~128KB total is fine on the process stack. */
    unsigned char acc[2 + UDP_FRAME_MAX];
    size_t acclen = 0;
    unsigned char dgram[UDP_FRAME_MAX];

    for (;;) {
        fd_set rfds;
        FD_ZERO(&rfds);
        FD_SET(vsock_fd, &rfds);
        FD_SET(u, &rfds);
        struct timeval tv = { .tv_sec = UDP_IDLE_BACKSTOP, .tv_usec = 0 };
        int r = select(maxfd, &rfds, NULL, NULL, &tv);
        if (r < 0) { if (errno == EINTR) continue; break; }
        if (r == 0) break;  /* idle backstop */

        /* vsock -> udp: accumulate bytes, extract whole frames, send each. */
        if (FD_ISSET(vsock_fd, &rfds)) {
            ssize_t n = read(vsock_fd, acc + acclen, sizeof(acc) - acclen);
            if (n == 0) break;  /* Android closed the flow */
            if (n < 0) {
                if (errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR) break;
            } else {
                acclen += (size_t)n;
                for (;;) {
                    if (acclen < 2) break;
                    size_t plen = ((size_t)acc[0] << 8) | acc[1];
                    if (acclen < 2 + plen) break;
                    (void)send(u, acc + 2, plen, 0); /* best-effort; plen 0 = valid empty datagram */
                    size_t consumed = 2 + plen;
                    memmove(acc, acc + consumed, acclen - consumed);
                    acclen -= consumed;
                }
            }
        }

        /* udp -> vsock: one datagram per recv, framed. write_all may block on
         * EAGAIN waiting for vsock writability; acceptable for a single-flow relay
         * (the host drains the reply stream on its own reader). */
        if (FD_ISSET(u, &rfds)) {
            ssize_t n = recv(u, dgram, sizeof(dgram), 0);
            if (n >= 0) {
                unsigned char hdr[2] = {
                    (unsigned char)(((size_t)n >> 8) & 0xff),
                    (unsigned char)((size_t)n & 0xff),
                };
                if (write_all(vsock_fd, hdr, 2) < 0) break;
                if (n > 0 && write_all(vsock_fd, dgram, (size_t)n) < 0) break;
            } else if (errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR) {
                break;
            }
        }
    }
    close(u);
    close(vsock_fd);
}

/* Forks the parent off — this function never returns in the child. sync_fd
 * carries the bind/listen result back to the parent (see tcp_listener_main). */
static void udp_listener_main(int vport, const char *host, int gport, int sync_fd) {
    setpgid(0, 0);
    int s = vsock_listen(vport);
    char ok = (s < 0) ? 0 : 1;
    if (write(sync_fd, &ok, 1) != 1) { /* parent gone; nothing to do */ }
    close(sync_fd);
    if (s < 0) _exit(1);
    close_inherited_fds(s);
    LOG_I("udp listener: vsock:%d → %s:%d", vport, host, gport);
    for (;;) {
        int c = accept4(s, NULL, NULL, SOCK_CLOEXEC);
        if (c < 0) { if (errno == EINTR) continue; break; }
        pid_t child = fork();
        if (child < 0) { close(c); continue; }
        if (child == 0) {
            close_inherited_fds(c);
            udp_relay(c, host, gport);
            _exit(0);
        }
        close(c);
    }
    _exit(0);
}

/* ── Listener spawn ─────────────────────────────────────────────────────── */

static int spawn_listener(int vport, const char *host, int gport, int is_udp) {
    int idx = listener_find(vport);
    if (idx >= 0) {
        if (pid_alive(listeners[idx].pid)) {
            if (listeners[idx].is_udp == is_udp && listeners[idx].gport == gport &&
                strcmp(listeners[idx].host, host) == 0) {
                return 0;  // already running with this exact target
            }
            /* Target changed (host, guest port, or protocol): tear down the
             * old listener the same way handle_remove does, then fall
             * through to spawn the replacement below. */
            pid_t old_pid = listeners[idx].pid;
            if (kill(-old_pid, SIGTERM) < 0 && errno == ESRCH) kill(old_pid, SIGTERM);
            LOG_I("spawn vsock:%d: target changed, replacing listener (pid %d)", vport, (int)old_pid);
            /* Wait (bounded) for the old listener to actually exit before forking
             * the replacement; otherwise the new child's bind() on the same
             * AF_VSOCK port can race the old socket's teardown and fail with
             * EADDRINUSE, losing the forward. reap_children() may win the reap
             * out from under us, so poll pid_alive() rather than waitpid(). */
            for (int waited = 0; waited < 50 && pid_alive(old_pid); waited++) usleep(10000);
        }
        listener_remove(idx);  // dead row, or the one just replaced above
    }
    /* Sync pipe: the child reports its bind/listen result so we record the
     * {vport,pid} row only once the listener is actually accepting. */
    int sync_pipe[2];
    if (pipe(sync_pipe) < 0) {
        LOG_E("spawn vsock:%d pipe() failed: %s", vport, strerror(errno));
        return -1;
    }
    pid_t pid = fork();
    if (pid < 0) { close(sync_pipe[0]); close(sync_pipe[1]); return -1; }
    if (pid == 0) {
        close(sync_pipe[0]);  // child keeps only the write end
        if (is_udp) udp_listener_main(vport, host, gport, sync_pipe[1]);  // never returns
        else        tcp_listener_main(vport, host, gport, sync_pipe[1]);  // never returns
        _exit(127);  // defensive: the *_listener_main calls never return
    }
    close(sync_pipe[1]);  // parent keeps the read end; the child is the sole writer
    char ok = 0;
    ssize_t n = read(sync_pipe[0], &ok, 1);
    close(sync_pipe[0]);
    if (n != 1 || ok != 1) {
        /* Bind/listen failed: the child has already exited (or is exiting). Don't
         * advertise it and don't signal pid — it may be reaped and its pid
         * recycled (see handle_remove); the SIGCHLD handler reaps the child. */
        LOG_W("spawn vsock:%d: listener did not come up", vport);
        return -1;
    }
    setpgid(pid, pid);
    if (listener_add(vport, pid, is_udp, host, gport) < 0) {
        if (kill(-pid, SIGTERM) < 0 && errno == ESRCH) kill(pid, SIGTERM);
        return -1;
    }
    return 1;
}

/* ── Control command handlers ───────────────────────────────────────────── */

static void handle_resize(int rows, int cols) {
    /* /dev/hvc0 since the perf fix — virtio-console runs at line-rate,
     * unlike the old PL011 ttyS0 path that throttled TUI redraws to
     * ~14 KB/s. The getty also moved to hvc0 (per podroid-getty + the
     * `podroid.tty=hvc0` cmdline marker AVF now passes). */
    /* O_RDWR: TIOCSWINSZ is a set/write ioctl; O_RDONLY can be rejected. */
    int fd = open("/dev/hvc0", O_RDWR | O_NOCTTY);
    if (fd >= 0) {
        struct winsize ws = { .ws_row = (unsigned short)rows, .ws_col = (unsigned short)cols };
        if (ioctl(fd, TIOCSWINSZ, &ws) < 0) {
            LOG_W("TIOCSWINSZ /dev/hvc0 failed: %s", strerror(errno));
        }
        close(fd);
    }
    FILE *f = fopen(TERM_SIZE_PATH, "w");
    if (f) { fprintf(f, "%d %d\n", rows, cols); fclose(f); }
}

static void handle_add(int vport, const char *proto, const char *host, int gport) {
    int is_udp = (strcmp(proto, "udp") == 0);
    int r = spawn_listener(vport, host, gport, is_udp);
    if (r < 0) {
        LOG_W("ADD vsock:%d failed", vport);
        return;
    }
    LOG_I("ADD vsock:%d %s → %s:%d", vport, proto, host, gport);
}

static void handle_remove(int vport) {
    int idx = listener_find(vport);
    if (idx < 0) { LOG_W("REMOVE vsock:%d — no such listener", vport); return; }
    pid_t pid = listeners[idx].pid;
    /* If the listener already died on its own, the kernel may have recycled its
     * pid for an unrelated process group — kill(-pid) would signal the wrong
     * processes. Probe with kill(pid,0) first and, if gone, just prune the
     * stale row; never signal a recycled pid. */
    if (!pid_alive(pid)) {
        listener_remove(idx);
        LOG_I("REMOVE vsock:%d — listener (pid %d) already gone, pruned", vport, (int)pid);
        return;
    }
    /* Kill the whole process group (listener + its in-flight splice children,
     * which share pgid == listener pid) so existing connections stop too, not
     * just the acceptor. Fall back to the bare pid if the group is somehow
     * gone. */
    if (kill(-pid, SIGTERM) < 0 && errno == ESRCH) kill(pid, SIGTERM);
    listener_remove(idx);
    LOG_I("REMOVE vsock:%d (pid %d)", vport, (int)pid);
}

/* ── Control loop ───────────────────────────────────────────────────────── */

static void trim_newline(char *s) {
    size_t n = strlen(s);
    while (n > 0 && (s[n-1] == '\n' || s[n-1] == '\r')) s[--n] = '\0';
}

static void ctl_loop(int fd) {
    /* dup() clears FD_CLOEXEC, so set it back: a TCP listener forked by a live
     * ADD must not inherit this read handle. Capture dfd separately so the
     * dup'd fd is closed on an fdopen failure instead of being orphaned. */
    int dfd = dup(fd);
    if (dfd >= 0) {
        int dflags = fcntl(dfd, F_GETFD, 0);
        if (dflags >= 0) fcntl(dfd, F_SETFD, dflags | FD_CLOEXEC);
    }
    FILE *in = (dfd >= 0) ? fdopen(dfd, "r") : NULL;
    FILE *out = fdopen(fd, "w");
    if (!in || !out) {
        if (in) fclose(in); else if (dfd >= 0) close(dfd);
        if (out) fclose(out); else close(fd);
        return;
    }
    setvbuf(out, NULL, _IOLBF, 0);  // line-buffered replies
    char line[512];
    while (fgets(line, sizeof(line), in)) {
        trim_newline(line);
        if (strncmp(line, "RESIZE ", 7) == 0) {
            int rows = 0, cols = 0;
            if (sscanf(line + 7, "%d %d", &rows, &cols) == 2 && rows > 0 && cols > 0)
                handle_resize(rows, cols);
            else LOG_W("bad RESIZE: '%s'", line);
        } else if (strncmp(line, "ADD ", 4) == 0) {
            int vport = 0, gport = 0;
            char proto[8], host[64];
            if (sscanf(line + 4, "%d %7s %63s %d", &vport, proto, host, &gport) == 4 &&
                (strcmp(proto, "tcp") == 0 || strcmp(proto, "udp") == 0))
                handle_add(vport, proto, host, gport);
            else LOG_W("bad ADD: '%s'", line);
        } else if (strncmp(line, "REMOVE ", 7) == 0) {
            int vport = 0;
            if (sscanf(line + 7, "%d", &vport) == 1)
                handle_remove(vport);
            else LOG_W("bad REMOVE: '%s'", line);
        } else if (strcmp(line, "PING") == 0) {
            fprintf(out, "PONG\n");
            fflush(out);
        } else if (strcmp(line, "SYNC") == 0) {
            sync();              /* flush all guest filesystem buffers to virtio-blk */
            fprintf(out, "SYNCED\n");
            fflush(out);
        } else if (line[0] != '\0') {
            LOG_W("unknown command: '%s'", line);
        }
    }
    fclose(in);
    fclose(out);
}

/* ── Downloads 9p rendezvous (downloads-9p mode) ────────────────────────────
 *
 * Android's AvfDownloadsShare connectVsock()s to this port and runs its own
 * in-process 9p2000.L server directly against the resulting socket. We are
 * only the transport rendezvous: accept the one connection, hand its fd to
 * the guest kernel's 9p client via `mount -t 9p -o trans=fd`, then get out of
 * the way. The mounted fd carries live 9p protocol traffic between the guest
 * kernel and Android's server - we must never read() or write() it ourselves,
 * that would steal or corrupt protocol bytes.
 *
 * mount(2) with trans=fd has the kernel take its own reference to rfdno/wfdno
 * (net/9p/trans_fd.c fgets them), so our own fd stays valid across the call
 * and keeps working afterwards purely as an observation handle: poll() only
 * reports readiness, it never consumes data, so it is safe to watch even
 * though the bytes flowing through the fd belong to the 9p session. POLLHUP/
 * POLLERR/POLLNVAL are reported by the kernel regardless of the requested
 * events, so polling with events=0 gives us exactly "the peer went away"
 * without also waking on ordinary 9p traffic.
 */
static void downloads_9p_serve(void) {
    int s = socket(AF_VSOCK, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (s < 0) { LOG_E("downloads-9p: vsock socket() failed: %s", strerror(errno)); return; }
    int one = 1;
    setsockopt(s, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
    struct sockaddr_vm sa;
    memset(&sa, 0, sizeof(sa));
    sa.svm_family = AF_VSOCK;
    sa.svm_cid    = VMADDR_CID_ANY;
    sa.svm_port   = PODROID_DOWNLOADS_VSOCK_PORT;
    if (bind(s, (struct sockaddr *)&sa, sizeof(sa)) < 0) {
        LOG_E("downloads-9p: vsock bind(%d) failed: %s", PODROID_DOWNLOADS_VSOCK_PORT, strerror(errno));
        close(s);
        return;
    }
    if (listen(s, 1) < 0) {
        LOG_E("downloads-9p: vsock listen(%d) failed: %s", PODROID_DOWNLOADS_VSOCK_PORT, strerror(errno));
        close(s);
        return;
    }
    LOG_I("downloads-9p: listening on vsock:%d", PODROID_DOWNLOADS_VSOCK_PORT);

    for (;;) {
        int cfd = accept4(s, NULL, NULL, 0);
        if (cfd < 0) {
            if (errno == EINTR) continue;
            LOG_E("downloads-9p: accept() failed: %s", strerror(errno));
            break;
        }

        mkdir(PODROID_DOWNLOADS_MOUNT, 0755);
        char opts[160];
        snprintf(opts, sizeof(opts),
                 "trans=fd,rfdno=%d,wfdno=%d,version=9p2000.L,msize=262144,cache=none,access=any",
                 cfd, cfd);
        if (mount("downloads", PODROID_DOWNLOADS_MOUNT, "9p", 0, opts) < 0) {
            LOG_W("downloads-9p: mount failed: %s", strerror(errno));
            close(cfd);
            continue;  // non-fatal - wait for Android to reconnect
        }
        LOG_I("downloads-9p: mounted %s", PODROID_DOWNLOADS_MOUNT);

        /* Block until the connection dies (Android side closed, VM stopping,
         * sharing toggled off), then unmount and go back to accept(). */
        for (;;) {
            struct pollfd pfd = { .fd = cfd, .events = 0 };
            int pr = poll(&pfd, 1, -1);
            if (pr < 0) {
                if (errno == EINTR) continue;
                break;
            }
            if (pfd.revents & (POLLHUP | POLLERR | POLLNVAL)) break;
        }

        LOG_I("downloads-9p: connection ended, unmounting");
        if (umount2(PODROID_DOWNLOADS_MOUNT, MNT_DETACH) < 0 && errno != EINVAL && errno != ENOENT)
            LOG_W("downloads-9p: umount2 failed: %s", strerror(errno));
        close(cfd);
        // loop back to accept() - best-effort reconnect
    }
    close(s);
}

/* ── Main + config bootstrap ────────────────────────────────────────────── */

static int ctl_vport = -1;

/* Read config; spawn listener children for tcp/udp lines; remember the ctl
 * vport so main() can bind it. */
static void parse_config_and_bootstrap(const char *path) {
    FILE *f = fopen(path, "r");
    if (!f) { LOG_W("no config at %s — control-only mode", path); return; }
    char line[512];
    while (fgets(line, sizeof(line), f)) {
        char *p = line;
        while (*p && isspace((unsigned char)*p)) p++;
        if (*p == '\0' || *p == '#') continue;
        trim_newline(p);
        int vport = -1;
        char kind[8] = {0};
        if (sscanf(p, "%d %7s", &vport, kind) < 2) continue;
        if (strcmp(kind, "ctl") == 0) {
            ctl_vport = vport;
        } else if (strcmp(kind, "tcp") == 0 || strcmp(kind, "udp") == 0) {
            char host[64] = {0};
            int gport = 0;
            int is_udp = (strcmp(kind, "udp") == 0);
            if (sscanf(p, "%d %*s %63s %d", &vport, host, &gport) == 3) {
                if (spawn_listener(vport, host, gport, is_udp) != 0)
                    LOG_W("startup: spawn listener for vsock:%d failed", vport);
            }
        }
    }
    fclose(f);
}

static void reap_children(int signum) {
    (void)signum;
    int status;
    while (waitpid(-1, &status, WNOHANG) > 0) {}
}

int main(int argc, char **argv) {
    /* Ignore SIGPIPE in every mode - splice_loop's write() and the downloads
     * 9p rendezvous both prefer EPIPE on a dead peer over the default
     * SIGPIPE disposition. */
    signal(SIGPIPE, SIG_IGN);

    if (argc >= 2 && strcmp(argv[1], "downloads-9p") == 0) {
        downloads_9p_serve();
        return 0;
    }

    /* Reap dead listener children; don't accumulate zombies. */
    struct sigaction sa = {0};
    sa.sa_handler = reap_children;
    sa.sa_flags = SA_NOCLDSTOP | SA_RESTART;
    sigemptyset(&sa.sa_mask);
    sigaction(SIGCHLD, &sa, NULL);

    parse_config_and_bootstrap(CONFIG_PATH);

    if (ctl_vport < 0) {
        LOG_E("no 'ctl' line in %s — exiting", CONFIG_PATH);
        return 1;
    }

    int s = socket(AF_VSOCK, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (s < 0) { LOG_E("ctl socket() failed: %s", strerror(errno)); return 1; }
    int one = 1;
    setsockopt(s, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
    struct sockaddr_vm sa_v;
    memset(&sa_v, 0, sizeof(sa_v));
    sa_v.svm_family = AF_VSOCK;
    sa_v.svm_cid    = VMADDR_CID_ANY;
    sa_v.svm_port   = (unsigned int)ctl_vport;
    if (bind(s, (struct sockaddr *)&sa_v, sizeof(sa_v)) < 0) {
        LOG_E("ctl bind(%d) failed: %s", ctl_vport, strerror(errno));
        close(s);
        return 1;
    }
    if (listen(s, 4) < 0) {
        LOG_E("ctl listen failed: %s", strerror(errno));
        close(s);
        return 1;
    }
    LOG_I("ctl: listening on vsock:%d", ctl_vport);

    for (;;) {
        /* accept4(SOCK_CLOEXEC): the ctl connection fd (and its dup in
         * ctl_loop) must not leak into TCP listeners forked by a live ADD. */
        int c = accept4(s, NULL, NULL, SOCK_CLOEXEC);
        if (c < 0) { if (errno == EINTR) continue; break; }
        /* No SO_RCVTIMEO: the control channel is a single trusted, long-lived
         * connection from the host that is idle most of the time — it only
         * carries RESIZE/ADD/REMOVE on user events, often many minutes apart.
         * A read timeout makes fgets() return NULL on idle, so ctl_loop tears
         * the connection down, and the host (which never reconnects) then
         * silently drops every later port-forward ADD. Block on fgets. */
        ctl_loop(c);  // ctl_loop takes ownership of `c` via fdopen — don't close here
    }
    return 0;
}
