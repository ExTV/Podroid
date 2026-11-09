# CLAUDE.md

Guidance for Claude Code (and any AI assistant or new contributor) working in this repository. This is the deep map: read it before touching anything. Keep it accurate when you change the code.

## What Is This

Podroid is an Android app that runs a real **Alpine 3.24** Linux VM on stock Android 8+ (arm64) to provide rootless **Podman / Docker / LXC** containers and an in-app **X11 desktop** - no root, no custom recovery.

- **Interchangeable VM backends** behind one interface (`VmEngine`):
  - **QEMU (TCG)** - software emulation, the default, needs no special permission.
  - **AVF (pKVM)** - hardware-accelerated via the Android Virtualization Framework on Pixel-class devices, after a one-time `pm grant`.
  - **UML (experimental, opt-in)** - User-Mode Linux: the guest kernel runs as an ordinary host process, so guest code runs natively. Needs no special permission. Never chosen by Auto.
- The guest is a standard Alpine root with **OpenRC as PID 1**. System services live in `/etc/init.d/podroid-*` on a read-only squashfs; a persistent ext4 overlay captures user changes (`apk add`, `rc-update add`).
- An embedded **Termux-based terminal**, an **X11/VNC viewer** (Xvnc + PulseAudio), and a **guest-to-Android bridge** (`podroid-notify` / `podroid-forward`) round it out.

## Key Facts

| | |
|---|---|
| Package | `com.excp.podroid` (debug: `com.excp.podroid.debug`) |
| Version | `versionName` / `versionCode` in `app/build.gradle.kts` |
| Min / target SDK | 26 (Android 8) / 36 |
| Architecture | arm64 (`aarch64`) only |
| Guest | Alpine 3.24 squashfs + persistent ext4 overlay, OpenRC PID 1 |
| Kernel | custom Linux, version pinned by `podroidKernelVersion` in `gradle.properties` |
| QEMU | version pinned by `podroidQemuVersion` in `gradle.properties` |
| UI | Jetpack Compose + Material 3, single Activity |
| DI | Hilt (constructor injection) |
| Async | Coroutines + StateFlow |
| Persistence | Jetpack DataStore (no database; Room is declared but unused) |
| Terminal | vendored Termux fork as local Gradle modules (`terminal-view`, `terminal-emulator`) |
| Languages | English + 中文 (Chinese); see `data/repository/LanguageManager.kt` + `res/values-zh/` |

## Build Commands

All native/VM components are coordinated by `build-all.sh` (Docker-cached):

```bash
./build-all.sh kernel      # custom kernel only (podroid_kernel.config)
./build-all.sh initramfs   # kernel + minimal initramfs
./build-all.sh rootfs      # Alpine squashfs -> app/src/main/assets/alpine-rootfs.squashfs
./build-all.sh qemu        # QEMU + native helpers via Docker (slow first time)
./build-all.sh uml         # UML kernels (4K/16K pages) + stubs + supervisor + GPL source tarball (out/)
./build-all.sh apk         # Android APK via Gradle
./build-all.sh all         # everything
./build-all.sh deploy      # APK + install in place (adb install -r)
./build-all.sh test        # boot validation: installs, polls console.log for "Ready!"
```

**APK only:** `./gradlew assembleDebug` / `./gradlew installDebug`.

**Unit tests:** `./gradlew :app:testDebugUnitTest` (use the `:app:` form, not the bare task).

**Monitor VM boot:**
```bash
adb logcat -s PodroidQemu
adb shell run-as com.excp.podroid.debug cat files/console.log   # debug build
```

**Release builds** are signed via `signingConfigs.release` (keystore `podroid-release.jks`), fed by the `PODROID_RELEASE_STORE_FILE` / `_PASSWORD` / `_KEY_ALIAS` / `_KEY_PASSWORD` Gradle properties. Release `applicationId = com.excp.podroid`; debug gets `applicationIdSuffix = ".debug"` + `versionNameSuffix = "-debug"`. Any code comparing the local version against an upstream release tag must strip an optional `-debug` suffix (see `UpdateRepository.checkForUpdate`).

Native binaries require **16KB page alignment** (`-Wl,-z,max-page-size=16384`) - mandatory on Android 13+; verified by an ELF parser in `build-all.sh`.

## Architecture

### Engine abstraction (the most important thing to understand)

Everything VM-related goes through `engine/VmEngine.kt`, a single interface implemented by three backends and one router:

- **`QemuEngine.kt`** - QEMU/TCG. Software emulation, SLIRP user-mode networking, control via QMP and virtio-console Unix sockets. No special permission.
- **`engine/avf/AvfEngine.kt`** - AVF/pKVM. Uses the Android Virtualization Framework (reached by reflection in `AvfReflect.kt`); networking and control ride **vsock**. Hardware-accelerated; needs `MANAGE_VIRTUAL_MACHINE` + `USE_CUSTOM_VIRTUAL_MACHINE` granted via `pm grant`.
- **`engine/uml/UmlEngine.kt`** - UML. Runs the supervisor `libpodroid-uml.so` (source `podroid-uml.c`), which starts the UML kernel plus a guest-less QEMU used only as the SLIRP network router. Support classes in the same package: `UmlCommandBuilder` (kernel command line), `UmlProbe` / `UmlProbeStore` / `UmlStatus` (on-device compatibility probe and demotion record), `UmlFailurePolicy` (what counts as UML failing), `UmlProcessTree` (host process accounting). Opt-in only: Auto never picks UML; with UML selected, `EngineHolder.decideBackend` uses UML only when the probe passed, otherwise the Auto pick (AVF if usable, else QEMU). No USB passthrough.
- **`EngineHolder.kt`** - the single Hilt binding for `VmEngine`. Picks the concrete engine at startup based on `EngineSelection` (Auto / AVF / QEMU / UML), watches Settings for backend changes, and routes imperative calls + flow access to whichever is current. Also owns the live diff that applies `PortForwardRepository` changes to the running VM, and the UML policy: probe before the first UML boot, fall back to what Auto would pick when the probe fails or UML is demoted, and demote on a UML failure.

When you touch VM behavior, decide whether it is backend-neutral (put it behind `VmEngine` / `EngineHolder`) or backend-specific (inside `QemuEngine`, `AvfEngine` or `UmlEngine`).

### Data flow

```
Terminal UI (Compose)
    | TerminalSession (vendored Termux JNI)
libpodroid-bridge.so  <->  terminal.sock + ctrl.sock  <->  VM  <->  hvc0 + hvc1
boot monitor          <->  serial.sock (QEMU) / console stream (AVF)  ->  console.log + boot-stage detector
QmpClient             <->  qmp.sock (QEMU only)        -> runtime port forwards (netdev_add/remove)
HostRequestServer     <->  host.sock/hvc2 (QEMU) | vsock:9101 (AVF)    <- podroid-notify / podroid-forward
```

QEMU exposes these Unix sockets under `context.filesDir`, each with one role:

- **`terminal.sock`** ↔ virtio-console `/dev/hvc0` - primary terminal I/O. getty runs on hvc0; `libpodroid-bridge.so` relays it to a Termux PTY.
- **`ctrl.sock`** ↔ virtio-console `/dev/hvc1` - resize channel. The bridge debounces SIGWINCH bursts and writes one `RESIZE rows cols\n`; a guest resize daemon `stty`s hvc0.
- **`serial.sock`** ↔ PL011 `/dev/ttyAMA0` - boot-log sink only. The boot monitor streams kernel + init output into `console.log` and the boot-stage detector.
- **`qmp.sock`** - QEMU Machine Protocol for runtime port forwarding and USB hot-plug.
- **`host.sock`** ↔ virtio-console `/dev/hvc2` - the guest→Android host bridge (see below).

On **UML** the supervisor recreates the same sockets: `serial.sock` (console, ssl/con0), `terminal.sock` (guest `/dev/ttyS0`), `ctrl.sock` (`/dev/ttyS1`, resize), `host.sock` (`/dev/ttyS2`, host bridge) and `qmp.sock` (the router QEMU's own), so the boot monitor, terminal bridge, host bridge and port forwards are shared with QEMU. The guest sees the `podroid.backend=uml` cmdline marker, disks `ubda` (storage) / `ubdb` (squashfs), and a probe mode (`podroid.probe=1`) used by `UmlProbe`.

On **AVF** there is no QMP and no PL011: the console is captured via `ConsoleFanout.kt`, control/resize go over `VsockControlChannel.kt`, port forwards over `VsockPortForwarder.kt`, and the host bridge over vsock port 9101.

### Boot pipeline

1. The engine launches the VM and starts the boot monitor (`QemuBootMonitor.kt` for QEMU; the console stream for AVF). `BootStageDetector.kt` scans the rolling console buffer (last ~1KB, not per-`read()` chunk - fast devices split markers like `Ready!` across reads).
2. **`init-podroid`** (initramfs, ~45 lines): mounts the persistent ext4 (`/dev/vda` → upper) and the read-only squashfs (`/dev/vdb` → lower), stacks an overlayfs, moves the mounts into the new root, and `switch_root`s into `/sbin/init` (busybox).
3. Busybox `/sbin/init` reads `/etc/inittab` and starts **OpenRC** (runlevels are pre-symlinked at build time - chroot-into-aarch64 doesn't work on an x86_64 builder).
4. OpenRC services on the squashfs do all system bringup:
   - `podroid-bootstrap` - kernel modules, cgroup v2, devpts/shm/mqueue, sysctl, ZRAM swap, `mount --make-rshared /`, container dirs.
   - `podroid-network` - eth0 up, addressing, default route, `/etc/resolv.conf`.
   - `podroid-resize` - reads `RESIZE rows cols` from `/dev/hvc1`, `stty`s `/dev/hvc0`.
   - `podroid-hostd` - the host-bridge daemon (all backends).
   - `podroid-vsock` - the AVF control/forward agent (AVF boots only).
   - `dropbear` - SSH, when enabled.
   - `podroid-ready` - emits `Starting SSH...` / `Almost ready...` / `Ready!`, the markers `BootStageDetector` matches; `Ready!` flips state to `Running` and auto-starts the terminal bridge.

**Why `switch_root`, not `chroot`:** an earlier version `chroot`ed into the overlay, which broke `podman exec -it` - `setns(MNT)` in `crun exec` resets `fs->root`, so the exec'd process saw raw kernel paths (`/mnt/overlay/proc`) instead of `/proc`. `switch_root` reorganizes the kernel mount tree itself, so namespace forks see a clean `/`.

### Guest → Android host bridge

Lets guest processes call back to Android. Guest side: `podroid-hostd` (multi-call C binary, `build-rootfs/host-bridge/podroid-hostd.c`) owns a guest-local `AF_UNIX` socket `/run/podroid-host.sock`; `argv[0]` dispatch also exposes it as `podroid-notify`, `podroid-forward`, and `podroid-phone-ip` (symlinks). The daemon relays one request line / one response line to Android over a backend transport: `/dev/hvc2` on QEMU, `AF_VSOCK:9101` on AVF (chosen by the `podroid.backend=avf` cmdline marker), `/dev/ttyS2` on UML (`podroid.backend=uml`). Android side: `engine/hostbridge/` - `HostRequestServer` reads requests over a `HostTransport` (`QemuHostTransport` via `LocalSocket` to `host.sock` (the UML supervisor exposes the same socket), or `AvfHostTransport` via `connectVsock`), `HostRequestDispatcher` parses them, and routes to `NotificationPoster` (posts via `NotificationManagerCompat`), `PortForwardRepository` (rules persist and `EngineHolder` applies them live), or the `PHONE-IP` verb (`podroid-phone-ip` CLI, backed by `NetworkUtils.allLocalIpv4`, returning a base64 `iface address` table, primary first). `PodroidService` starts/stops the server over the VM lifecycle. Stop or parent cancellation shuts down the active transport to unblock reads; a transport returned by a late native connect is closed without dispatching. Android accepts only complete UTF-8 request lines up to 8191 bytes (matching the guest buffer). Free-text fields are base64 (`HostProtocol.kt`) so UTF-8 survives. The daemon also self-originates a `STATS containers=<n>` push (inotify-driven, debounced) whenever the podman container count changes, which `HostRequestDispatcher` persists to the `last_container_count` DataStore key for `HomeViewModel` to show live on Home - the same channel on all backends, no polling.

> **Gotcha:** `/dev/hvc2` is a virtio-console **TTY** that defaults to echo on. The daemon must `cfmakeraw()` it, or the TTY echoes Android's responses back and the protocol desyncs after the first request. AVF (a raw vsock socket) is unaffected.

### Downloads sharing (`engine/avf/AvfDownloadsShare.kt` + `engine/avf/ninep/`)

All three backends share the Android Downloads directory, each by a different transport: QEMU via virtio-9p (`-fsdev local` + `virtio-9p-pci`, mounted by `podroid-bootstrap`), AVF via the in-process 9p server below over vsock (mounted by `podroid-downloads`), and UML via hostfs (the host passes `hostfs=<Downloads>` plus a `podroid.downloads=1` flag; `podroid-bootstrap` mounts the hostfs root with no path). `build-uml/kernel/hostfs-confine.patch` confines every hostfs mount to the `hostfs=` root (any other mount path, `..` included, gets EPERM, and no root means no mount), and with sharing off the root is `files/uml-hostfs-disabled`, which never exists. The AVF path serves the real Android Downloads directory into the guest by running an in-process **9p2000.L** server (`Ninep2000LServer.kt`, ~886 LoC; wire format in `NinepCodec.kt`) over vsock. This is why the guest can read and write user files without the app holding broad storage permissions, and it is how `podroid-backup` archives land in `Downloads/Podroid/backups` for `ContainerBackupRepository` to list.

The 9p server itself is AVF-only; the share is on by a user setting on every backend, so features built on it must still degrade cleanly when it is off or unavailable.

### USB passthrough (`engine/usb/UsbPassthroughManager.kt`)

QEMU backend only (`UsbPassthroughManager.backendSupportsPassthrough`: on UML the QMP socket is the guest-less router's, so the manager logs and does nothing there and on AVF). An unprivileged app can't open `/dev/bus/usb`, so it takes the already-open fd from `UsbManager`/`UsbDeviceConnection` and streams it to QEMU over `qmp.sock` as SCM_RIGHTS (`add-fd`), then hot-plugs with `device_add usb-host,hostdevice=/dev/fdset/N`. A code-registered `BroadcastReceiver` (no manifest `device_filter.xml`) is live only while the VM is `Running`. Gated by the `usb_passthrough_enabled` setting, which also makes `buildCommand()` emit `-device qemu-xhci`. Needs a libusb-enabled QEMU build.

### X11 viewer (`x11/` + `ui/screens/x11/`)

In-app viewer that talks RFB to `Xvnc` in the guest, with touch→mouse, soft-keyboard, external mouse/keyboard, fullscreen, rotation lock, resolution presets, and PCM audio over PulseAudio loopback. Backed by always-on implicit VNC/audio port forwards.

### Internationalization

`data/repository/LanguageManager.kt` + `res/values/` / `res/values-zh/`. `MainActivity.attachBaseContext` wraps the context for the saved locale (read synchronously from a cache file before Hilt is available); changing the language in Settings persists it and recreates the Activity. Don't reintroduce hardcoded user-facing strings - use `stringResource` and keep `values` / `values-zh` in sync.

### Navigation

Single-activity Compose app: `ui/navigation/NavGraph.kt` routes `setup → home → terminal / settings / x11`. `TerminalViewModel` is scoped outside the `NavHost` so the session survives screen changes. The setup wizard shows until setup is marked complete in DataStore.

## Project Structure

```
/
├── app/                              # Android module
│   └── src/main/
│       ├── java/com/excp/podroid/
│       │   ├── MainActivity.kt           # single Activity, locale wrap, WindowSizeClass
│       │   ├── PodroidApplication.kt     # Hilt app, asset extraction on first run
│       │   ├── engine/
│       │   │   ├── VmEngine.kt           # backend interface
│       │   │   ├── EngineHolder.kt       # Hilt binding; routes to current engine; port-forward diff
│       │   │   ├── EngineModule.kt, EngineSelection.kt
│       │   │   ├── QemuEngine.kt         # QEMU/TCG backend, buildCommand()
│       │   │   ├── QemuBootMonitor.kt, BootStageDetector.kt
│       │   │   ├── QmpClient.kt          # QMP: port forwards + USB add-fd
│       │   │   ├── ResizeNotifyingSession.kt, VmState.kt
│       │   │   ├── avf/                  # AVF/pKVM backend (engine, reflection, vsock, console)
│       │   │   │   ├── AvfDownloadsShare.kt  # live Downloads share, AVF transport of the share (see below)
│       │   │   │   ├── AvfDiagnostics.kt     # AVF diagnostic + smoke-test entry point
│       │   │   │   └── ninep/                # in-process 9p2000.L server (Ninep2000LServer, NinepCodec)
│       │   │   ├── uml/                  # UML backend (engine, command builder, probe, failure policy, process tree)
│       │   │   ├── hostbridge/           # guest->Android bridge (transport, server, dispatcher, notify)
│       │   │   │   └── HeadlessModeManager.kt # single source of truth for server (headless) mode
│       │   │   ├── control/              # ControlProvider: shell-only `adb shell content call` test interface
│       │   │   └── usb/                  # UsbPassthroughManager
│       │   ├── service/PodroidService.kt # foreground service; owns VM lifecycle, wakelock, notification; also VmControlReceiver (boot autostart + START_VM/STOP_VM automation intents)
│       │   ├── data/repository/          # Settings, PortForward, Update, Language, ContainerBackup (all DataStore)
│       │   ├── di/                       # Hilt module
│       │   ├── util/                     # NetworkUtils, ShellQuote, HostMetrics + VmLoadSampler (Status screen), DeviceResourcePolicy (load-balance sizing)
│       │   ├── x11/                      # X11/VNC viewer engine
│       │   └── ui/                       # Compose: navigation, theme, screens/{setup,home,terminal,settings,x11,status,backup}, components
│       ├── res/values, res/values-zh/    # strings (EN + Chinese)
│       ├── assets/                       # vmlinuz-virt, initrd.img, alpine-rootfs.squashfs (all gitignored, built locally),
│       │                                 # qemu/, colors/ (122), fonts/ (13), ui-fonts/
│       └── jniLibs/arm64-v8a/            # native executables (see below)
├── terminal-view/, terminal-emulator/   # vendored Termux fork (local Gradle modules)
├── init-podroid                         # initramfs bootstrap: overlay + switch_root
├── podroid-bridge.c                     # PTY <-> virtio-console relay -> libpodroid-bridge.so
├── podroid-uml.c                        # UML supervisor -> libpodroid-uml.so
├── podroid-launcher.c                   # exec wrapper that ties QEMU's lifetime to the app -> libpodroid-launcher.so
├── Dockerfile                           # kernel + initramfs + QEMU build
├── build-tools/                         # static assets used during Docker builds
│   └── cross-android-aarch64.ini        # Meson cross-compilation config for aarch64-android26
├── build-uml/                           # UML kernel (4K/16K), stub and supervisor Docker build (build-uml.sh, Dockerfile.uml)
├── build-rootfs/
│   ├── Dockerfile.rootfs, build-rootfs.sh
│   ├── vsock-agent/                     # podroid-vsock-agent.c (AVF control/forward agent)
│   ├── host-bridge/                     # podroid-hostd.c (guest->Android bridge daemon + CLIs)
│   └── files/etc/                       # OpenRC services + configs baked into the squashfs
├── build-all.sh, gradle.properties, build.gradle.kts, settings.gradle.kts
└── README.md, CONTRIBUTING.md, CREDITS.md
```

## Native Binaries (`app/src/main/jniLibs/arm64-v8a/`)

ELF executables renamed `.so` for APK packaging; run via `ProcessBuilder` / `TerminalSession`, not loaded as JNI libraries. All 16KB-aligned.

| File | What it is |
|---|---|
| `libqemu-system-aarch64.so` | QEMU TCG emulator |
| `libpodroid-bridge.so` | PTY ↔ virtio-console relay (from `podroid-bridge.c`) |
| `libpodroid-launcher.so` | exec wrapper for QEMU process lifetime (from `podroid-launcher.c`) |
| `libslirp.so` | SLIRP user-mode networking (soname patched `.so.0`→`.so`) |
| `libuml-4k.so`, `libuml-16k.so` | UML guest kernels for 4KB and 16KB page hosts (`./build-all.sh uml`) |
| `libumlstub-4k.so`, `libumlstub-16k.so` | UML stub executables matching each kernel |
| `libpodroid-uml.so` | UML supervisor (from `podroid-uml.c`) |

The terminal emulator JNI is built from the vendored `terminal-emulator` module (rebuilt for 16KB pages), not shipped as a prebuilt here.

## QEMU command construction (`QemuEngine.buildCommand()`)

- `-serial unix:serial.sock` (boot log) + a `virtio-serial-pci` bus carrying three virtconsoles: `terminal.sock` (hvc0), `ctrl.sock` (hvc1), `host.sock` (hvc2, the host bridge - **order matters**, the guest expects host bridge on hvc2).
- `-qmp unix:qmp.sock` for runtime port forwards + USB.
- RAM, CPU count, and user-editable extras (`-cpu`, `-accel`, RNG, etc.) from `SettingsRepository`.
- Two virtio block devices: `/dev/vda` ← `storage.img` (writable ext4 overlay upper, resized on first boot), `/dev/vdb` ← `alpine-rootfs.squashfs` (read-only zstd lower).
- SLIRP networking; `-device qemu-xhci` when USB passthrough is enabled.

## Build pipelines

- **`Dockerfile`** - custom Linux kernel (arm64 defconfig + `podroid_kernel.config` modules + `forced_builtin.config` forcing overlayfs / netfilter / bridge / veth / tun / FUSE / IPv6 etc. to `=y`) and QEMU cross-compiled against the NDK. A build-time check greps the resolved `.config` and **fails the build** if any critical option isn't `=y` (guards against silent Kconfig demotion from unmet tristate deps). QEMU needs `--enable-libusb` (for passthrough) and a few Android/Bionic patches; `build-all.sh qemu` may need `docker build --network=host`.
- **`build-rootfs/Dockerfile.rootfs`** - fetches the Alpine minirootfs, runs `build-rootfs.sh` (apk-installs alpine-base + openrc + podman + crun + fuse-overlayfs + docker + lxc + dropbear + iptables/nftables + bridge-utils, sets root password `podroid`, seals file caps on `newuidmap`/`newgidmap`, copies the OpenRC services and the cross-compiled `podroid-vsock-agent` + `podroid-hostd`, wires runlevels via direct symlinks), then `mksquashfs -comp zstd` (kernel ships `CONFIG_SQUASHFS_ZSTD=y`).

## Performance tuning (TCG path; KVM is impossible without root)

In `QemuEngine.buildCommand()`: `tcg,thread=multi`, larger `tb-size` for ≥2GB RAM, dedicated `iothread` on `virtio-blk-pci`, `-cpu max,pauth-impdef=on`. More vCPUs are not better here: on an 8-core phone, 8 measured slower than 4 on every metric. Guest cmdline: `mitigations=off` (safe inside TCG), per-device `mq-deadline`. `init-podroid`/bootstrap: ZRAM lz4 swap at 1.5x RAM (lz4 averages about 3x, so the backing store stays well under RAM). `CONFIG_EXT4_FS_SECURITY=y` + `CONFIG_SQUASHFS_XATTR=y` keep `security.capability` xattrs across the overlay (rootless podman's `newuidmap` needs them). What won't work without root: `io_uring` (seccomp), CPU affinity, KSM, TAP networking, host hugepages.

## Quirks & gotchas

- **`CONFIG_DEVTMPFS_MOUNT=y` pre-mounts `/dev` before `/init` runs.** `init-podroid` must use `mount ... || true` for the devtmpfs line — the kernel's pre-populated `/dev` is sufficient and a second `mount(2)` returns EBUSY. Also: `size=` is not valid for devtmpfs in kernels where it is ramfs-backed (causes EINVAL). Either failure exits util-linux with `MNT_EX_FAIL=32`; `set -e` propagates `exit(32)`, encoded as `exitcode=0x00002000` ("Attempted to kill init!"). Always keep the `|| true`.
- **util-linux `mount` stops option parsing at the first non-option argument** when `POSIXLY_CORRECT` is set. Put `-o` flags *before* device and mountpoint (`mount -t proc -o noexec,nosuid,nodev proc /proc`), not after. Options placed after the mountpoint are silently dropped, causing `mount(2)` to be called with `flags=0` and returning EPERM — which util-linux prints as "must be superuser to use mount" even for root.
- **`Dockerfile` heredoc gotcha.** Docker BuildKit's parser treats `[section]` lines inside `RUN ... << 'EOF'` heredocs as unknown Dockerfile instructions and aborts the parse. Instead of using shell heredocs in `RUN` for multi-line config files, use `COPY build-tools/<file>` from the build context. The Meson cross-compilation config lives in `build-tools/cross-android-aarch64.ini` for this reason.
- **Backend asymmetry is the #1 source of bugs.** QEMU = SLIRP + QMP + virtio-console (TTY); AVF = DHCP + vsock (raw sockets); UML = SLIRP via the router QEMU + QMP + UML serial-line TTYs (raw mode needed, like hvc2). Test every backend the change touches. The host bridge's `cfmakeraw` on hvc2 (above) is a concrete example. AVF TCP forwarding preserves half-close: EOF shuts down only the destination output; failures/cancellation abort both endpoints, with raw-vsock `Os.shutdown` serialized before descriptor closure.
- **UML runs one host process per guest process.** Every guest process (every container process) is a host process, so a busy guest is exposed to Android's phantom-process cap; the engine accounts for this through `UmlProcessTree`. A SIGKILL with no fatal signature is treated as Android killing it, not as UML failing.
- **UML needs the foreground service.** Because it is a plain child process, the cached-app freezer freezes the whole VM when the app is not held by `PodroidService`. Never run it outside the service.
- **UML probe and demotion.** Before the first UML boot `EngineHolder` runs `UmlProbe` (init-podroid probe mode: SMP, fork/exec, disk write, DNS through the router). The probe ignores the user's extra kernel cmdline, so it tests the device, not the settings. A failed probe is persisted (FAILED, network and timeout failures included, so a device that always fails does not pay a probe on every start), and a UML run that fails (no `Ready!` before the boot timeout, a fatal console signature, or another nonzero exit) demotes UML (DEMOTED). Either way the device falls back to what Auto would pick until the app or OS is updated or the user taps "Try UML again" (shown for FAILED and DEMOTED; `uml-retry`, which answers `error vm-active` while a VM is starting or running). The record is keyed by build fingerprint, versionCode and bundled kernel. A "busy" probe (UML processes already exist) is never persisted.
- **hostfs confinement is not a sandbox.** Under UML, guest kernel-mode code runs with the app uid's privileges, so the hostfs root restriction closes the easy path (a guest `mount -t hostfs`), not a determined guest root that finds a guest-kernel bug (`CONFIG_MODULES` is off, so it cannot just load a module).
- **Supervisor exit code 75 means the router died, but only under a healthy kernel.** `libpodroid-uml.so` exits 75 (`EX_TEMPFAIL`) when the guest-less QEMU router dies while the kernel runs; that is not a UML failure and does not demote. A kernel that dies on its own (a fatal console signature such as a panic, or its own nonzero status) takes precedence over 75: `classifyUmlExit` checks the fatal signature first and demotes.
- **Boot detection** scans a rolling buffer, not per-read chunks (fast devices split markers).
- **Bridge stderr is silenced** (`dup2(/dev/null, STDERR)`): it runs as a `TerminalSession` subprocess whose stderr IS the PTY. Never add `fprintf(stderr, ...)` to `podroid-bridge.c`.
- **`forceUpdateSizeFromView` must not multiply by scaledDensity** - `TerminalView` passes the raw int textSize to `Paint`; mismatched math renders TUI apps in the wrong grid.
- **SLIRP's ICMP works here** - `ping 8.8.8.8` from the guest gets replies (libslirp proxies echo through an unprivileged ICMP socket, which Android grants app UIDs; the `ttl=255` on replies is SLIRP synthesizing them). A `ping: bad address` is therefore a DNS failure, not an ICMP one. SLIRP's DNS forwarder *is* unreliable on Android: `10.0.2.3` resolves upstream by reading the host's `/etc/resolv.conf`, which Android does not have, so the guest is given up to two distinct device IPv4 resolvers in order plus deduplicated public fallbacks (or the original `10.0.2.3`/public list when no valid device resolver is available).
- **Privileged ports**: the app can't bind host ports < 1024 (no `CAP_NET_BIND_SERVICE`), so SSH is on 9922, not 22. Forward to a high host port.
- **Reflection into the vendored Termux fork** (`mTermSession`, `mEmulator`, `mCurrentDecSetFlags`) is kept by `app/proguard-rules.pro`.
- **Persistence is sacred**: DataStore keys and `filesDir` paths survive every release; renames need migration. Updates must install in place (same `applicationId` + signing key, monotonic `versionCode`).

## VM migration / upgrades (how the guest updates without a reset)

Asset extraction readiness fails if any bundled asset cannot be extracted, so `PodroidService` publishes a localized `VmState.Error` and aborts startup rather than booting a mixed asset set. Extraction retries on the next app process launch; temporary-file cleanup targets only known asset destinations and is best-effort so a stale temporary sibling cannot block an already-current destination.

The guest system layer updates across app versions with **no VM reset and no data loss**, on every backend (UML boots the same initrd and squashfs, so the machinery applies to it too). The machinery:

- **Plain overlay (never re-add metacopy/index/redirect).** `init-podroid` mounts the rootfs overlay as `lowerdir=/mnt/lower,upperdir=/mnt/persist/upper,workdir=/mnt/persist/work` only. Plain overlayfs tolerates a swapped lower, so a new squashfs (re-extracted by `PodroidApplication` on every update) goes live on the next boot while the persistent upper is preserved. **Do not re-add `metacopy=on`/`index=on`/`redirect_dir=on`** - they bind the upper to a specific lower and reintroduce the corruption-on-update bug (the whole reason resets used to be needed).
- **Version anchor.** The squashfs ships `/etc/podroid/system-version` (baked from `versionCode` by `build-all.sh` -> `Dockerfile.rootfs` ARG -> `build-rootfs.sh`). The last-applied version persists at `/mnt/persist/.podroid/applied-version`.
- **One-time legacy normalization.** `init-podroid` runs `podroid-overlay-normalize` (shipped in the squashfs, invoked via `/mnt/lower`, before the overlay is stacked) once per device (guarded by `/mnt/persist/.podroid/normalized`) to strip pre-existing `metacopy`/`redirect`/`index` state from uppers created by the old metacopy overlay. No-op on fresh/normalized uppers.
- **Imperative hooks.** `podroid-migrate` (OpenRC, runs `before podroid-bootstrap`) executes `/etc/podroid/migrations/<v>.sh` for `applied < v <= system-version` in order, then advances `applied-version` atomically. **To ship a fixup in a new release** (e.g. enable a newly-added service): add `build-rootfs/files/etc/podroid/migrations/<versionCode>.sh`, idempotent, install it in `build-rootfs.sh`. Pure file additions/changes need NO script - the overlay union surfaces them.
- **Reliability:** the marker advances only after migration completes (crash -> idempotent re-run); nothing auto-wipes `/mnt/persist`; `init-podroid` keeps its `FATAL -> exec sh` recovery shell.

## Common tasks

- **Kotlin/UI change** → `./gradlew assembleDebug && ./gradlew installDebug`.
- **OpenRC service / package list / guest CLI change** → edit under `build-rootfs/`, `./build-all.sh rootfs`, rebuild APK. New/changed system files go live on the **next VM boot** with no reset (the plain-overlay union surfaces them; see "VM migration / upgrades"). The exception is a path the user already modified - it lives in the persistent upper and keeps the user's version until removed, so use a `/etc/podroid/migrations/<v>.sh` script for that case.
- **`init-podroid` change** → `./build-all.sh initramfs`, rebuild APK.
- **New boot stage** → emit the marker in the right OpenRC service + match it in `BootStageDetector`.
- **New setting** → add a DataStore key + Flow in `SettingsRepository`, UI in `SettingsScreen`, setter in `SettingsViewModel`, and plumb into `VmConfig`/`buildCommand()` if it affects the VM.
- **New user-facing string** → add to `res/values` AND `res/values-zh`, use `stringResource`.
- **Verify before "done"**: compile, run `:app:testDebugUnitTest`, and for behavior changes install and walk the flow on a device (unit tests don't catch DI/graph or backend-specific issues).

## Proving a change on a device

Unit tests cover pure logic only. Anything touching VM behavior, the engine boundary, or a user-visible flow is unproven until it runs on hardware, and **the backends need different devices**:

| Backend | Device requirement | Extra setup |
|---|---|---|
| QEMU | any arm64 Android 8+ device | none; this is the default path |
| UML | any arm64 Android 8+ device | none (no special permission); select it with `set-backend --arg uml`, force-stop and relaunch; clear a failed probe or a demotion with `uml-retry` |
| AVF | a device reporting `android.software.virtualization_framework` (Pixel-class) | `pm grant` both `MANAGE_VIRTUAL_MACHINE` and `USE_CUSTOM_VIRTUAL_MACHINE`, then force-stop the app |

Check AVF capability before assuming a device can exercise it:

```bash
adb shell pm list features | grep virtualization
```

A device without that feature can *never* run `AvfEngine`, no matter the `EngineSelection` setting. `EngineHolder.pick()` re-evaluates only at process start, so a fresh `pm grant` does not take effect until the app is force-stopped and relaunched.

**A change that touches several backends is unproven until it has been installed and walked on each.** Backend asymmetry is the #1 source of bugs (see Quirks & gotchas), and `AvfEngine.kt` is the most-churned file in the repository, so a QEMU-only test is the easiest way to ship a regression.

### Driving the app from adb

`engine/control/ControlProvider.kt` answers `adb shell content call`, so a device test does not have to tap through the UI. It ships in both build types, because release is where R8 bugs show up. Each call returns one line, `ok ...` or `error ...`.

```bash
C="adb shell content call --uri content://com.excp.podroid.control --method"   # debug: com.excp.podroid.debug.control
$C state                              # vm, backend, selection, boot stage, forwards, versionCode, uml=<status>[:<reason>]
$C set-backend --arg qemu             # auto | avf | qemu | uml; force-stop and relaunch to re-pick
$C uml-retry                          # clear a failed UML probe or demotion (same as "Try UML again" in Settings)
$C forward-add --arg tcp:8080:80      # goes through PortForwardRepository, so EngineHolder applies it live
$C forward-remove --arg tcp:8080:80
$C backups                            # archive names the app sees
$C navigate --arg container_backup    # home | terminal | settings | status | container_backup
adb shell am broadcast -a com.excp.podroid.action.START_VM -n com.excp.podroid/.service.VmControlReceiver   # and STOP_VM
```

The framework does **not** permission-check `ContentProvider.call()`, so the manifest `android:permission` is only defense in depth. The real gate is `isCallerAllowed()` in `call()`: uid 0, uid 2000 (shell), or a caller holding `DUMP`. Keep that check first in `call()`; without it any installed app could open a LAN-facing port forward. New methods must go through the same repositories the UI uses, never straight to an engine.

Reading the VM console depends on build type: `run-as` works on debug, but **release builds are not `run-as`-able**, so use `su` or **Settings → Diagnostics → Export Log** there.

```bash
adb shell run-as com.excp.podroid.debug cat files/console.log   # debug only
```
