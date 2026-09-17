/*
 * podroid-launcher — sets PR_SET_PDEATHSIG before exec'ing QEMU.
 *
 * Why: ProcessBuilder.start() spawns QEMU as a child of our app process, but
 * Linux doesn't auto-kill the child when the parent dies. So when Android
 * uninstalls/reinstalls the APK (parent SIGKILL'd by the installer), QEMU
 * survives as an orphan under PPID=1 and keeps consuming RAM/swap until
 * reboot or manual kill.
 *
 * Fix: a tiny wrapper that calls prctl(PR_SET_PDEATHSIG, SIGKILL) before
 * exec'ing the real QEMU binary. The kernel then guarantees the child dies
 * with the parent, regardless of the cause (uninstall, OOM, force-stop, crash).
 *
 * PR_SET_PDEATHSIG is a Linux syscall stable since Linux 2.6, supported on
 * every Android version (9 through 17+). It is NOT cleared by a regular
 * execve, so the flag carries through into QEMU.
 *
 * Args:
 *   argv[0] = launcher path (set by ProcessBuilder)
 *   argv[1] = real QEMU binary path
 *   argv[2..] = QEMU arguments
 *
 * Build (mirrors podroid-bridge):
 *   ${CC} --sysroot=${LLVM}/sysroot -target aarch64-linux-android28 \
 *       -fPIE -pie -Wl,-z,max-page-size=16384 \
 *       podroid-launcher.c -o libpodroid-launcher.so
 */

#include <signal.h>
#include <stdio.h>
#include <sys/prctl.h>
#include <unistd.h>

int main(int argc, char *argv[]) {
    if (argc < 2) {
        fprintf(stderr, "podroid-launcher: usage: launcher <qemu-path> [args...]\n");
        return 2;
    }

    /* Tell the kernel to send us SIGKILL when our parent process exits.
     * Failure here is non-fatal — we still launch QEMU, just without the
     * orphan-cleanup guarantee (i.e. revert to today's behavior). */
    (void)prctl(PR_SET_PDEATHSIG, SIGKILL, 0, 0, 0);

    /* Race guard: between ProcessBuilder.start() and our prctl() call, the
     * parent may already have died. If so, we've already been reparented to
     * init (PID 1). Bail immediately so we never become an orphan ourselves. */
    if (getppid() == 1) {
        return 1;
    }

    /* exec the real QEMU. PR_SET_PDEATHSIG is preserved across execve()
     * (only cleared on setuid execs, which we never do). */
    execv(argv[1], &argv[1]);

    /* execv only returns on failure. */
    perror("podroid-launcher: execv");
    return 127;
}
