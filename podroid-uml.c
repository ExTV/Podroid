/*
 * podroid-uml - supervisor for the User-Mode Linux backend.
 *
 * Usage: libpodroid-uml.so QEMU|- WORKDIR -- KERNEL [kernel args...]
 *
 * Runs Podroid's QEMU as a guest-less SLIRP router (-M none, a hub joining
 * the "user" netdev net0 to a "dgram" netdev on an fd; UML's fd vector
 * transport is one Ethernet frame per datagram, the same framing) plus the
 * UML kernel, and exposes the Unix sockets QEMU exposes for its own guests,
 * so the Android side (boot monitor, terminal bridge, host bridge, QMP port
 * forwards) is shared:
 *
 *   WORKDIR/serial.sock    con0 (plus the kernel's stdout/stderr), read-only
 *   WORKDIR/terminal.sock  ssl0 = guest /dev/ttyS0
 *   WORKDIR/ctrl.sock      ssl1 = guest /dev/ttyS1 (resize channel)
 *   WORKDIR/host.sock      ssl2 = guest /dev/ttyS2 (host bridge)
 *   WORKDIR/qmp.sock       QEMU's own QMP socket
 *
 * Each socket takes one client at a time; further connects wait in the
 * listen backlog until the current client leaves, like a QEMU
 * "server,nowait" chardev. While no client is attached, serial.sock keeps
 * the most recent 64 KiB of console output and hands it to the next client;
 * the other channels drop guest output, as QEMU does. The guest never blocks
 * on an absent client. Pass "-" as QEMU to run without a network.
 *
 * Env: PODROID_UML_QEMU_ARGS  extra options for the net0 "user" netdev
 *      PODROID_UML_STUB       stub binary, passed to the kernel as stub_exe=
 *      TMPDIR                 also the kernel's HOME, so the umid directory
 *                             is $TMPDIR/.uml/podroid (UML derives uml_dir
 *                             from $HOME, not $TMPDIR)
 *      PODROID_UML_SECCOMP    debug only: raw sock_filter array installed
 *                             before anything is forked
 *
 * SIGTERM/SIGINT/SIGHUP: mconsole "cad" (the guest runs /sbin/poweroff),
 * then up to 60 s for the kernel to exit before the process group is
 * SIGKILLed. On every exit path the group is SIGKILLed and reaped, and the
 * umid directory and socket files are removed. Exit code: the kernel's exit
 * code, or 128 + the signal that killed it.
 *
 * The QEMU router dying while the kernel runs is fatal: the same graceful
 * stop runs, and the exit code is 75 (EX_TEMPFAIL) whatever the kernel
 * returned, so the caller can tell a dead network router from a kernel
 * failure.
 */
#define _GNU_SOURCE
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <poll.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/time.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#define NCH 4
#define CH_SERIAL 0
#define STOP_GRACE_MS 60000
#define DRAIN_MS 2000
#define REAP_MS 5000
#define UMID "podroid"
#define EXIT_ROUTER_DIED 75	/* EX_TEMPFAIL */
#define ACCEPT_BACKOFF_MS 1000

struct chan {
	const char *name;
	int gfd;		/* supervisor end of the kernel socketpair */
	int lfd;		/* listening socket */
	int cfd;		/* connected client, or -1 */
	int keep;		/* buffer guest output while no client */
	int accept_logged;	/* accept() failure already reported */
	long long accept_after;	/* listener not polled before this time */
	char *ring;		/* guest -> client */
	size_t cap, head, len;
	char in[16384];		/* client -> guest */
	size_t in_off, in_len;
	char path[PATH_MAX];
};

static struct chan ch[NCH] = {
	{ .name = "serial.sock", .keep = 1, .cap = 65536 },
	{ .name = "terminal.sock", .cap = 16384 },
	{ .name = "ctrl.sock", .cap = 16384 },
	{ .name = "host.sock", .cap = 16384 },
};

static int wake[2] = { -1, -1 };
static volatile sig_atomic_t stop_req;
static char umid_dir[512], qmp_path[PATH_MAX];

static void on_stop(int sig)
{
	int e = errno;

	(void)sig;
	stop_req = 1;
	(void)!write(wake[1], "s", 1);
	errno = e;
}

static void on_chld(int sig)
{
	int e = errno;

	(void)sig;
	(void)!write(wake[1], "c", 1);
	errno = e;
}

/* An app process hands its children SIGTERM/SIGQUIT/SIGUSR1/SIGPIPE
 * blocked; exec keeps the mask and ignored dispositions. */
static void reset_signals(void)
{
	sigset_t none;
	int s;

	sigemptyset(&none);
	sigprocmask(SIG_SETMASK, &none, NULL);
	for (s = 1; s < 32; s++)
		if (s != SIGKILL && s != SIGSTOP)
			signal(s, SIG_DFL);
}

static int install_seccomp(const char *path)
{
	struct sock_filter *insns;
	struct sock_fprog prog;
	struct stat st;
	ssize_t n;
	int fd;

	fd = open(path, O_RDONLY | O_CLOEXEC);
	if (fd < 0) {
		fprintf(stderr, "podroid-uml: seccomp %s: %s\n", path, strerror(errno));
		return -1;
	}
	if (fstat(fd, &st) || st.st_size <= 0 ||
	    (size_t)st.st_size % sizeof(*insns) ||
	    (size_t)st.st_size / sizeof(*insns) > BPF_MAXINSNS) {
		fprintf(stderr, "podroid-uml: seccomp %s: not a sock_filter array\n", path);
		close(fd);
		return -1;
	}
	insns = malloc(st.st_size);
	n = insns ? read(fd, insns, st.st_size) : -1;
	close(fd);
	if (n != st.st_size) {
		fprintf(stderr, "podroid-uml: seccomp %s: short read\n", path);
		free(insns);
		return -1;
	}
	prog.len = st.st_size / sizeof(*insns);
	prog.filter = insns;
	if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) ||
	    prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &prog)) {
		fprintf(stderr, "podroid-uml: seccomp %s: %s\n", path, strerror(errno));
		free(insns);
		return -1;
	}
	free(insns);
	return 0;
}

static long long now_ms(void)
{
	struct timespec ts;

	clock_gettime(CLOCK_MONOTONIC, &ts);
	return (long long)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

static void set_nonblock(int fd)
{
	fcntl(fd, F_SETFL, fcntl(fd, F_GETFL) | O_NONBLOCK);
}

static int fill_sun(struct sockaddr_un *a, const char *path)
{
	memset(a, 0, sizeof(*a));
	a->sun_family = AF_UNIX;
	if (strlen(path) >= sizeof(a->sun_path)) {
		fprintf(stderr, "podroid-uml: socket path too long: %s\n", path);
		return -1;
	}
	strcpy(a->sun_path, path);
	return 0;
}

/* Bind under a temporary name and rename once listening, so a client that
 * sees the file never gets ECONNREFUSED from a bound-but-not-listening
 * socket (QemuBootMonitor connects once, as soon as the file exists). */
static int listen_at(const char *path)
{
	struct sockaddr_un a;
	char tmp[PATH_MAX];
	int fd;

	snprintf(tmp, sizeof(tmp), "%s.new", path);
	if (fill_sun(&a, tmp))
		return -1;
	unlink(tmp);
	unlink(path);
	fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0);
	if (fd < 0 || bind(fd, (struct sockaddr *)&a, sizeof(a)) ||
	    listen(fd, 4) || rename(tmp, path)) {
		fprintf(stderr, "podroid-uml: listen %s: %s\n", path, strerror(errno));
		if (fd >= 0)
			close(fd);
		unlink(tmp);
		return -1;
	}
	return fd;
}

/* Removes the umid directory (pid file, mconsole socket). Not recursive:
 * UML only puts plain files there. */
static void remove_umid_dir(void)
{
	char f[PATH_MAX];
	struct dirent *e;
	DIR *d = opendir(umid_dir);

	if (!d)
		return;
	while ((e = readdir(d))) {
		if (!strcmp(e->d_name, ".") || !strcmp(e->d_name, ".."))
			continue;
		snprintf(f, sizeof(f), "%s/%s", umid_dir, e->d_name);
		unlink(f);
	}
	closedir(d);
	rmdir(umid_dir);
}

/* Sends one mconsole command (protocol: arch/um/drivers/mconsole.h). */
static int mconsole(const char *workdir, const char *cmd)
{
	struct { uint32_t magic, version, len; char data[512]; } q = {
		.magic = 0xcafebabe, .version = 2,
	};
	struct { uint32_t err, more, len; char data[512]; } r;
	struct sockaddr_un me, to;
	struct timeval tv = { .tv_sec = 2 };
	char path[PATH_MAX];
	int fd, rc = -1;

	snprintf(path, sizeof(path), "%s/mconsole", umid_dir);
	if (fill_sun(&to, path))
		return -1;
	snprintf(path, sizeof(path), "%s/uml-mc.sock", workdir);
	if (fill_sun(&me, path))
		return -1;
	q.len = snprintf(q.data, sizeof(q.data), "%s", cmd);
	fd = socket(AF_UNIX, SOCK_DGRAM | SOCK_CLOEXEC, 0);
	unlink(path);
	if (fd < 0 || bind(fd, (struct sockaddr *)&me, sizeof(me))) {
		fprintf(stderr, "podroid-uml: mconsole bind: %s\n", strerror(errno));
		goto out;
	}
	setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
	if (sendto(fd, &q, sizeof(q), 0, (struct sockaddr *)&to, sizeof(to)) < 0) {
		fprintf(stderr, "podroid-uml: mconsole %s: %s\n", cmd, strerror(errno));
		goto out;
	}
	if (recv(fd, &r, sizeof(r), 0) < 0)
		fprintf(stderr, "podroid-uml: mconsole %s: no reply: %s\n", cmd, strerror(errno));
	else if (r.err)
		fprintf(stderr, "podroid-uml: mconsole %s: error %u\n", cmd, r.err);
	/* The request was delivered either way; the shutdown deadline decides. */
	rc = 0;
out:
	if (fd >= 0)
		close(fd);
	unlink(path);
	return rc;
}

static void close_client(struct chan *c)
{
	close(c->cfd);
	c->cfd = -1;
	c->in_off = c->in_len = 0;
	if (!c->keep)
		c->head = c->len = 0;
}

/* One poll round over the wake pipe and every channel. */
static void pump(int timeout_ms)
{
	struct pollfd pf[1 + 3 * NCH];
	int idx[NCH][3], n = 0, i, k;
	char buf[16384];
	ssize_t r;
	long long now = now_ms();

	pf[n++] = (struct pollfd){ .fd = wake[0], .events = POLLIN };
	for (i = 0; i < NCH; i++) {
		struct chan *c = &ch[i];

		idx[i][0] = idx[i][1] = idx[i][2] = -1;
		if (c->gfd >= 0) {
			short ev = 0;

			/* Read guest output when there is room for it, or always
			 * when it would be dropped or overwrite old history. */
			if (c->cfd < 0 || c->len < c->cap)
				ev |= POLLIN;
			if (c->in_len)
				ev |= POLLOUT;
			if (ev) {
				idx[i][0] = n;
				pf[n++] = (struct pollfd){ .fd = c->gfd, .events = ev };
			}
		}
		if (c->cfd >= 0) {
			short ev = 0;

			if (!c->in_len)
				ev |= POLLIN;
			if (c->len)
				ev |= POLLOUT;
			idx[i][1] = n;
			pf[n++] = (struct pollfd){ .fd = c->cfd, .events = ev };
		} else if (c->lfd >= 0 && now < c->accept_after) {
			/* Backing off a failing accept(): wake up when it ends. */
			int wait = (int)(c->accept_after - now);

			if (timeout_ms < 0 || wait < timeout_ms)
				timeout_ms = wait;
		} else if (c->lfd >= 0) {
			idx[i][2] = n;
			pf[n++] = (struct pollfd){ .fd = c->lfd, .events = POLLIN };
		}
	}
	if (poll(pf, n, timeout_ms) <= 0)
		return;
	if (pf[0].revents & POLLIN)
		while (read(wake[0], buf, sizeof(buf)) > 0)
			;

	for (i = 0; i < NCH; i++) {
		struct chan *c = &ch[i];

		k = idx[i][0];
		if (k >= 0 && (pf[k].revents & (POLLIN | POLLHUP | POLLERR)) &&
		    (c->cfd < 0 || c->len < c->cap)) {
			size_t room = c->cfd < 0 ? sizeof(buf) : c->cap - c->len;

			if (room > sizeof(buf))
				room = sizeof(buf);
			r = read(c->gfd, buf, room);
			if (r == 0 || (r < 0 && errno != EAGAIN && errno != EINTR)) {
				close(c->gfd);
				c->gfd = -1;
				c->in_len = 0;
			} else if (r > 0 && (c->cfd >= 0 || c->keep)) {
				size_t j;

				for (j = 0; j < (size_t)r; j++) {
					if (c->len == c->cap) {	/* history only: drop oldest */
						c->head = (c->head + 1) % c->cap;
						c->len--;
					}
					c->ring[(c->head + c->len++) % c->cap] = buf[j];
				}
			}
		}
		if (k >= 0 && c->gfd >= 0 && (pf[k].revents & POLLOUT) && c->in_len) {
			r = send(c->gfd, c->in + c->in_off, c->in_len, MSG_NOSIGNAL);
			if (r > 0) {
				c->in_off += r;
				c->in_len -= r;
			} else if (r < 0 && errno != EAGAIN && errno != EINTR) {
				c->in_len = 0;
			}
		}

		/* Accept only after this round's guest I/O, which was sized for
		 * the client state at poll time. */
		k = idx[i][2];
		if (k >= 0 && (pf[k].revents & POLLIN)) {
			c->cfd = accept4(c->lfd, NULL, NULL, SOCK_NONBLOCK | SOCK_CLOEXEC);
			if (c->cfd < 0 && errno != EAGAIN && errno != EINTR &&
			    errno != ECONNABORTED) {
				/* e.g. EMFILE: the pending connection stays queued and
				 * the listener stays readable, so polling it again at
				 * once would spin. */
				if (!c->accept_logged)
					fprintf(stderr, "podroid-uml: accept %s: %s\n",
						c->name, strerror(errno));
				c->accept_logged = 1;
				c->accept_after = now_ms() + ACCEPT_BACKOFF_MS;
			}
			if (c->cfd < 0)
				c->cfd = -1;
		}

		k = idx[i][1];
		if (k < 0 || c->cfd < 0)
			continue;
		if (pf[k].revents & POLLOUT) {
			size_t chunk = c->head + c->len > c->cap ? c->cap - c->head : c->len;

			r = send(c->cfd, c->ring + c->head, chunk, MSG_NOSIGNAL);
			if (r > 0) {
				c->head = (c->head + r) % c->cap;
				c->len -= r;
			} else if (r < 0 && errno != EAGAIN && errno != EINTR) {
				close_client(c);
				continue;
			}
		}
		if (pf[k].revents & (POLLIN | POLLHUP | POLLERR)) {
			/* A client that hung up while its input is still queued for
			 * a guest that is not reading loses that input. */
			if (c->in_len) {
				close_client(c);
				continue;
			}
			r = read(c->cfd, c->in, sizeof(c->in));
			if (r == 0 || (r < 0 && errno != EAGAIN && errno != EINTR)) {
				close_client(c);
			} else if (r > 0 && i != CH_SERIAL && c->gfd >= 0) {
				c->in_off = 0;
				c->in_len = r;
			}
		}
	}
}

static int child_status_rc(int st)
{
	return WIFSIGNALED(st) ? 128 + WTERMSIG(st) : WEXITSTATUS(st);
}

int main(int argc, char **argv)
{
	int sp[2] = { -1, -1 }, cons[2], ssl[3][2];
	int i, st, rc = 1, net, kernel_done = 0, kernel_st = 0, stopping = 0;
	int router_died = 0, kill_sent = 0, kernel_killed = 0;
	pid_t self = getpid(), kernel = -1, qemu = -1, pid;
	long long deadline = 0;
	char *qargs = getenv("PODROID_UML_QEMU_ARGS"), *stub = getenv("PODROID_UML_STUB");
	char *sfile = getenv("PODROID_UML_SECCOMP"), *home = getenv("TMPDIR");
	const char *workdir;
	struct sigaction sa;
	int sz = 1 << 20;

	if (argc < 5 || strcmp(argv[3], "--")) {
		fprintf(stderr, "usage: podroid-uml QEMU|- WORKDIR -- KERNEL args...\n");
		return 2;
	}
	net = strcmp(argv[1], "-") != 0;
	workdir = argv[2];
	if (!home || !*home)
		home = (char *)workdir;
	snprintf(umid_dir, sizeof(umid_dir), "%s/.uml/" UMID, home);
	snprintf(qmp_path, sizeof(qmp_path), "%s/qmp.sock", workdir);

	reset_signals();
	signal(SIGPIPE, SIG_IGN);
	if (pipe2(wake, O_CLOEXEC | O_NONBLOCK)) {
		perror("podroid-uml: pipe");
		return 1;
	}
	memset(&sa, 0, sizeof(sa));
	sa.sa_handler = on_stop;
	sigaction(SIGTERM, &sa, NULL);
	sigaction(SIGINT, &sa, NULL);
	sigaction(SIGHUP, &sa, NULL);
	sa.sa_handler = on_chld;
	sa.sa_flags = SA_NOCLDSTOP;
	sigaction(SIGCHLD, &sa, NULL);

	/* Guest processes are the kernel's children; if the kernel dies first
	 * they are reparented here and can still be reaped. */
	prctl(PR_SET_CHILD_SUBREAPER, 1, 0, 0, 0);

	/* A kernel killed without cleanup leaves a pid file; if that pid has
	 * been reused UML refuses the umid and picks a random one, which would
	 * move the mconsole socket. */
	remove_umid_dir();

	for (i = 0; i < NCH; i++) {
		ch[i].gfd = ch[i].lfd = ch[i].cfd = -1;
		ch[i].ring = malloc(ch[i].cap);
		snprintf(ch[i].path, sizeof(ch[i].path), "%s/%s", workdir, ch[i].name);
	}
	if (socketpair(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0, cons)) {
		perror("podroid-uml: socketpair");
		goto out;
	}
	ch[CH_SERIAL].gfd = cons[0];
	for (i = 0; i < 3; i++) {
		if (socketpair(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0, ssl[i])) {
			perror("podroid-uml: socketpair");
			goto out;
		}
		ch[i + 1].gfd = ssl[i][0];
	}
	for (i = 0; i < NCH; i++) {
		if (!ch[i].ring) {
			fprintf(stderr, "podroid-uml: out of memory\n");
			goto out;
		}
		set_nonblock(ch[i].gfd);
		ch[i].lfd = listen_at(ch[i].path);
		if (ch[i].lfd < 0)
			goto out;
	}

	if (sfile && *sfile && install_seccomp(sfile))
		goto out;

	if (net) {
		if (socketpair(AF_UNIX, SOCK_DGRAM | SOCK_CLOEXEC, 0, sp)) {
			perror("podroid-uml: socketpair");
			goto out;
		}
		for (i = 0; i < 2; i++) {
			setsockopt(sp[i], SOL_SOCKET, SO_RCVBUF, &sz, sizeof(sz));
			setsockopt(sp[i], SOL_SOCKET, SO_SNDBUF, &sz, sizeof(sz));
		}
	}

	/* The kernel leads the process group: UML calls setsid() at startup,
	 * which fails for a group leader and so keeps it, and every guest
	 * process it forks, in the group that kill(-kernel) reaches. */
	kernel = fork();
	if (kernel == 0) {
		char **av = calloc(argc + 8, sizeof(char *)), arg[5][64], sarg[PATH_MAX + 16];
		int a = 0, cin, cout, nul = open("/dev/null", O_RDONLY | O_CLOEXEC);

		setpgid(0, 0);
		reset_signals();
		prctl(PR_SET_PDEATHSIG, SIGKILL, 0, 0, 0);
		if (getppid() != self || !av)
			_exit(127);
		setenv("HOME", home, 1);
		dup2(nul, 0);
		dup2(cons[1], 1);
		dup2(cons[1], 2);
		cin = dup(cons[1]);
		cout = dup(cons[1]);
		av[a++] = argv[4];
		if (stub && *stub) {
			snprintf(sarg, sizeof(sarg), "stub_exe=%s", stub);
			av[a++] = sarg;
		}
		for (i = 5; i < argc; i++)
			av[a++] = argv[i];
		if (net) {
			fcntl(sp[0], F_SETFD, 0);
			snprintf(arg[0], sizeof(arg[0]), "vec0:transport=fd,fd=%d", sp[0]);
			av[a++] = arg[0];
		}
		snprintf(arg[1], sizeof(arg[1]), "con0=fd:%d,fd:%d", cin, cout);
		av[a++] = arg[1];
		for (i = 0; i < 3; i++) {
			fcntl(ssl[i][1], F_SETFD, 0);
			snprintf(arg[2 + i], sizeof(arg[2 + i]), "ssl%d=fd:%d", i, ssl[i][1]);
			av[a++] = arg[2 + i];
		}
		av[a++] = "umid=" UMID;
		execv(av[0], av);
		fprintf(stderr, "podroid-uml: exec %s: %s\n", av[0], strerror(errno));
		_exit(127);
	}
	if (kernel < 0) {
		perror("podroid-uml: fork");
		goto out;
	}
	setpgid(kernel, kernel);

	if (net) {
		qemu = fork();
		if (qemu == 0) {
			char user[1024], dgram[96], qmp[PATH_MAX + 32];
			int nul = open("/dev/null", O_RDONLY | O_CLOEXEC);

			setpgid(0, kernel);
			reset_signals();
			prctl(PR_SET_PDEATHSIG, SIGKILL, 0, 0, 0);
			if (getppid() != self)
				_exit(127);
			dup2(nul, 0);
			fcntl(sp[1], F_SETFD, 0);
			snprintf(user, sizeof(user), "user,id=net0%s%s",
				 qargs && *qargs ? "," : "", qargs && *qargs ? qargs : "");
			snprintf(dgram, sizeof(dgram),
				 "dgram,id=d0,local.type=fd,local.str=%d", sp[1]);
			snprintf(qmp, sizeof(qmp), "unix:%s,server,nowait", qmp_path);
			execl(argv[1], argv[1], "-M", "none", "-nodefaults",
			      "-display", "none", "-qmp", qmp,
			      "-netdev", user, "-netdev", dgram,
			      "-netdev", "hubport,id=h0,hubid=0,netdev=net0",
			      "-netdev", "hubport,id=h1,hubid=0,netdev=d0",
			      (char *)NULL);
			fprintf(stderr, "podroid-uml: exec %s: %s\n", argv[1], strerror(errno));
			_exit(127);
		}
		if (qemu < 0) {
			perror("podroid-uml: fork");
			goto out;
		}
		setpgid(qemu, kernel);
		close(sp[0]);
		close(sp[1]);
		sp[0] = sp[1] = -1;
	}
	close(cons[1]);
	for (i = 0; i < 3; i++)
		close(ssl[i][1]);

	while (!kernel_done) {
		long long left = -1;

		if (stop_req && !stopping) {
			stopping = 1;
			if (mconsole(workdir, "cad"))
				break;
			deadline = now_ms() + STOP_GRACE_MS;
		}
		if (stopping) {
			left = deadline - now_ms();
			if (left <= 0)
				break;
		}
		pump(left < 0 ? -1 : (int)left);
		while ((pid = waitpid(-1, &st, WNOHANG)) > 0) {
			if (pid == kernel) {
				kernel_done = 1;
				kernel_st = st;
			} else if (pid == qemu) {
				qemu = -1;
				if (!stopping) {
					fprintf(stderr, "podroid-uml: qemu router exited (status %d); stopping the guest\n",
						child_status_rc(st));
					router_died = 1;
					stop_req = 1;
				}
			}
		}
	}

out:
	/* SIGKILL the whole group and reap it, re-sending the kill so anything
	 * forked in the meantime goes too. The kill is only sent while some
	 * child is still unreaped, so the pgid cannot have been recycled. */
	if (kernel > 0) {
		long long until = now_ms() + REAP_MS;

		for (;;) {
			pid = waitpid(-1, &st, WNOHANG);
			if (pid < 0 && errno == EINTR)
				continue;
			if (pid < 0)
				break;
			if (pid > 0) {
				if (pid == kernel && !kernel_done) {
					kernel_done = 1;
					kernel_st = st;
					kernel_killed = kill_sent;
				} else if (pid == qemu) {
					qemu = -1;
				}
				continue;
			}
			if (now_ms() > until) {
				fprintf(stderr, "podroid-uml: children still running after SIGKILL\n");
				break;
			}
			kill(-kernel, SIGKILL);
			kill_sent = 1;
			if (qemu > 0)
				kill(qemu, SIGKILL);
			usleep(20000);
		}
		if (kernel_done) {
			rc = child_status_rc(kernel_st);
			if (rc)
				fprintf(stderr, "podroid-uml: kernel exited with code %d\n", rc);
		}
		/* The router dying only explains the exit when the kernel was
		 * otherwise healthy. A panicking kernel signals its own process
		 * group, so the router can die first; then the kernel's own
		 * nonzero status is the truth. */
		if (router_died) {
			if (kernel_done && rc && !kernel_killed) {
				fprintf(stderr, "podroid-uml: kernel failed on its own (code %d); not reporting router death\n", rc);
			} else {
				rc = EXIT_ROUTER_DIED;
			}
		}

		/* Hand the last console bytes (power-off or panic tail) to a
		 * connected client before the sockets go away. */
		{
			long long until_drain = now_ms() + DRAIN_MS;

			for (;;) {
				int busy = 0;

				for (i = 0; i < NCH; i++)
					if (ch[i].gfd >= 0 || (ch[i].cfd >= 0 && ch[i].len))
						busy = 1;
				if (!busy || now_ms() > until_drain)
					break;
				pump(50);
			}
		}
	}
	for (i = 0; i < NCH; i++) {
		if (ch[i].lfd >= 0)
			unlink(ch[i].path);
	}
	unlink(qmp_path);
	remove_umid_dir();
	return rc;
}
