# Podroid AI Context

> **Last updated:** 2026-04-08
> **Purpose:** Complete project context for AI-assisted development without re-explaining structure every prompt.

---

## Project Overview

**Podroid** is an Android app that runs Linux containers (Podman) on arm64 Android devices without root required. It spins up a lightweight Alpine Linux VM using QEMU and provides a built-in serial terminal.

- **Package:** `com.excp.podroid`
- **Min SDK:** 28 (Android 9.0)
- **Target SDK:** 36
- **Architecture:** AArch64 only
- **License:** GNU GPL v2.0

---

## Tech Stack

| Layer | Technology |
|-------|------------|
| UI | Jetpack Compose (Material 3) |
| DI | Hilt |
| Async | Kotlin Coroutines + Flow |
| Persistence | DataStore Preferences |
| Terminal | Termux TerminalView (VT100/xterm emulation) |
| VM | QEMU TCG (no KVM) |
| Container Runtime | Podman + crun + netavark + slirp4netns |
| Init System | Custom two-phase initramfs (`init-podroid`) |
| VM Linux | Alpine Linux 3.23 |

---

## Architecture

### High-Level Flow
```
Android App
├── Foreground Service (PodroidService) ← keeps VM alive
├── PodroidQemu engine
│   ├── QEMU process (libqemu-system-aarch64.so)
│   ├── Serial socket → boot monitor
│   └── QMP socket (port forwarding, VM control)
└── Jetpack Compose UI
    └── Screens: Home, Terminal, Settings, Setup
```

### VM Boot Sequence
1. QEMU loads `vmlinuz-virt` + `initrd.img`
2. Phase 1 (`init-podroid`): Mounts persistent ext4 disk as overlayfs upper layer
3. Phase 2 (`--main`): Configures networking, Podman, Dropbear SSH, starts getty on ttyAMA0
4. Terminal connects to serial console

---

## Project Structure

### Root Level
```
/
├── app/                              # Android application
├── init-podroid                      # Custom VM init script (shell)
├── podroid-bridge.c                  # Native PTY↔serial.sock relay binary
├── build.gradle.kts                  # Root build config
├── settings.gradle.kts                # Gradle settings
├── gradle.properties                 # Gradle + QEMU version (11.0.0-rc2)
├── Dockerfile                        # Multi-stage initramfs builder
├── Dockerfile.qemu                   # QEMU Docker build
├── docker-build-initramfs.sh          # Initramfs build script
├── build-termux-android.sh           # Termux native library build
├── build-qemu-android.sh              # QEMU Android build
└── gradle/, gradlew, gradlew.bat     # Gradle wrapper
```

### app/src/main/java/com/excp/podroid/

```
├── MainActivity.kt                    # Single activity entry point
├── PodroidApplication.kt              # Hilt Application - asset extraction on first run
│
├── engine/
│   ├── PodroidQemu.kt                # QEMU lifecycle, serial.sock boot monitor, ctrl.sock chardev
│   │   └── KEY: releaseSerial() closes boot monitor so podroid-bridge can connect
│   ├── QmpClient.kt                  # QEMU QMP socket client for runtime port forwards
│   └── VmState.kt                   # Sealed class: Idle, Starting, Running, Paused, Saving, Resuming, Stopped, Error
│
├── service/
│   └── PodroidService.kt             # Foreground service, WakeLock, notifications
│       └── Actions: ACTION_START, ACTION_STOP | SSH port: 9922
│
├── data/repository/
│   ├── SettingsRepository.kt          # DataStore-backed settings (darkTheme, vmRam, vmCpus, storageSize, etc.)
│   ├── PortForwardRepository.kt      # Port forward rules persistence
│   └── UpdateRepository.kt          # GitHub release checker
│
├── di/
│   └── AppModule.kt                  # Hilt module (empty - constructor injection only)
│
└── ui/
    ├── navigation/
    │   └── NavGraph.kt              # Routes: SETUP, HOME, TERMINAL, SETTINGS
    │       └── TerminalViewModel is scoped outside NavHost (survives navigation)
    │
    ├── theme/
    │   ├── Theme.kt                 # Material You (dynamic colors) + dark/light
    │   ├── Color.kt                 # Purple80/Grey80/Cyan80 palette + status colors
    │   └── Type.kt                  # Typography (default Material 3)
    │
    └── screens/
        ├── home/
        │   ├── HomeScreen.kt        # Start/Stop VM, restart, open terminal, update dialog
        │   └── HomeViewModel.kt     # checkForUpdate(), startPodroid(), stopVm(), restartVm()
        │
        ├── terminal/
        │   ├── TerminalScreen.kt    # Termux TerminalView + extra keys bar, layout listener, auto keyboard
        │   └── TerminalViewModel.kt # TerminalSession with podroid-bridge subprocess, mouse support
        │
        ├── settings/
        │   ├── SettingsScreen.kt     # Sections: Terminal → VM Resources → Network → Appearance → Diagnostics → About
        │   └── SettingsViewModel.kt # Port forward CRUD, VM reset, console log export
        │
        └── setup/
            ├── SetupScreen.kt        # 3-page pager: Storage size, VM config + SSH, Downloads sharing
            └── SetupViewModel.kt     # completeSetup() saves to DataStore, emits notification permission request
```

### app/src/main/res/
```
├── AndroidManifest.xml               # INTERNET, WAKE_LOCK, VIBRATE, FOREGROUND_SERVICE, POST_NOTIFICATIONS
├── drawable/                        # ic_vm_notification.xml, ic_stop.xml
├── mipmap-anydpi/                  # App icons (ic_launcher.xml)
├── values/                          # strings.xml, colors.xml, themes.xml
└── xml/
    ├── file_paths.xml               # FileProvider paths
    ├── backup_rules.xml             # Full backup rules
    └── data_extraction_rules.xml    # Android 12+ extraction rules
```

### Assets (app/src/main/assets/)
```
├── vmlinuz-virt                      # Linux kernel (built by docker-build-initramfs.sh)
├── initrd.img                        # Alpine initramfs with Podman (~71MB, built by docker-build-initramfs.sh)
├── qemu/                            # QEMU BIOS/firmware files
├── colors/                          # 114 terminal color schemes (.properties files)
│   └── dracula.properties, nord.properties, gruvbox-dark.properties, etc.
└── fonts/                           # 13 curated terminal fonts (.ttf files)
    └── JetBrains-Mono.ttf, Fira-Code.ttf, CascadiaCode.ttf, etc.
```

### Native Libs (app/src/main/jniLibs/arm64-v8a/)
```
├── libqemu-system-aarch64.so         # QEMU executable (built by build-qemu-android.sh)
├── libslirp.so                       # SLIRP networking library
└── libpodroid-bridge.so              # Terminal bridge (built by build-qemu-android.sh from podroid-bridge.c)
```

---

## Key Constants & Values

| Item | Value |
|------|-------|
| QEMU binary | `libqemu-system-aarch64.so` in nativeLibDir |
| Kernel | `vmlinuz-virt` in filesDir |
| Initrd | `initrd.img` in filesDir |
| Storage image | `storage.img` in filesDir (ext4) |
| QMP socket | `qmp.sock` in filesDir |
| Serial console | ttyAMA0 → `serial.sock` in filesDir (unix socket, boot monitor + bridge) |
| Control channel | hvc0 → `ctrl.sock` in filesDir (virtio-console, resize signals) |
| QMP port (via socket) | 4444 (via unix socket) |
| VM IP | 10.0.2.15 |
| Default gateway | 10.0.2.2 |
| DNS (QEMU SLIRP) | 10.0.2.3 |
| Default DNS | 8.8.8.8, 1.1.1.1 |
| SSH host port (auto) | 9922 → VM:22 (Dropbear) |
| Storage sizes | 2, 4, 8, 16, 32, 64 GB |
| Default RAM | 512 MB |
| Default CPUs | 1 |
| Default font size | 20sp |
| Boot stages | "Starting QEMU..." → "Booting kernel..." → "Mounting storage..." → "Loading kernel modules..." → "Setting up overlay..." → "Configuring containers..." → "Waiting for network..." → "Network found" → "Almost ready..." → "Starting SSH..." → "Ready" |
| QEMU args | `-M virt,gic-version=3 -cpu max -accel tcg,thread=multi,tb-size=256` |
| Storage access mount | virtio-9p with `mount_tag=downloads` → `/mnt/downloads` in VM |
| Resize daemon | Background shell loop reads `RESIZE rows cols` from `/dev/hvc0`, calls `stty rows N cols M < /dev/ttyAMA0` |

---

## DataStore Keys

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `dark_theme` | Boolean | true | Dark mode |
| `vm_ram_mb` | Int | 512 | VM RAM in MB |
| `vm_cpus` | Int | 1 | VM CPU cores |
| `terminal_font_size` | Int | 20 | Font size in sp |
| `terminal_color_theme` | String | "default" | Selected color scheme name |
| `terminal_font` | String | "default" | Selected font name |
| `storage_gb` | Int | 2 | Persistent storage size |
| `storage_access_enabled` | Boolean | false | Downloads folder sharing |
| `setup_done` | Boolean | false | Initial setup completed |
| `ssh_enabled` | Boolean | false | SSH server in VM |
| `port_forwards` | Set\<String\> | {} | Serialized PortForwardRule |
| `dismissed_update_version` | String | null | Dismissed update version |

---

## Port Forwarding System

### Format
```
"tcp:8080:80" → PortForwardRule(hostPort=8080, guestPort=80, protocol="tcp")
```

### Service Presets (in SettingsScreen)
- **Pi-hole:** 5300→53(both), 8080→80(tcp)
- **Nginx:** 8080→80, 8443→443
- **Gitea:** 3000→3000, 2222→22
- **Grafana:** 3001→3000

### Runtime Management
- At VM start: rules passed as QEMU `-netdev hostfwd` args
- At runtime: QMP `hostfwd_add`/`hostfwd_remove` via `human-monitor-command`

---

## Terminal Architecture

### Current Design (Podroid-Bridge PTY)
QEMU serial console exposed as a Unix socket (`serial.sock`). A native bridge binary
(`libpodroid-bridge.so`) runs as the `TerminalSession` subprocess — Termux allocates a
real PTY for it. The bridge relays PTY ↔ serial socket bidirectionally with no filtering.
The bridge sets its PTY to raw mode via `cfmakeraw()` (VM's getty handles
echo/line editing). Window resize propagates via the PTY/SIGWINCH chain:

```
TerminalView layout change
  → TerminalView.updateSize()                 [uses renderer font metrics]
  → TerminalSession.updateSize(cols, rows)    [Termux JNI: ioctl TIOCSWINSZ on PTY master]
  → SIGWINCH → podroid-bridge
  → bridge reads new size via TIOCGWINSZ
  → writes "RESIZE rows cols\n" to ctrl.sock
  → VM resize daemon reads /dev/hvc0
  → stty rows N cols M < /dev/ttyAMA0         [Linux kernel sends SIGWINCH to fg process group]
  → nvim / htop / btop redraws correctly
```

Mouse tracking is fully supported: Termux TerminalView natively handles mouse events
(touch gestures, USB/Bluetooth mouse buttons and scroll). When TUI apps enable mouse
mode (CSI 1000h), mouse clicks and scrolls route to the PTY → bridge → serial → VM.

**Auto keyboard**: When TerminalScreen opens with VM running, the Android soft keyboard
is shown automatically via `InputMethodManager.showSoftInput()` so users can type immediately.

### Socket Files (in filesDir)
| File | Purpose |
|------|---------|
| `serial.sock` | QEMU serial chardev (ttyAMA0) — bridge connects here for terminal I/O |
| `ctrl.sock` | QEMU virtio-console chardev (hvc0) — bridge writes `RESIZE rows cols\n` here |
| `qmp.sock` | QEMU QMP control socket |

### Data Flow
```
Keyboard  → TerminalView → TerminalSession.write() → PTY master → bridge stdin → serial.sock → ttyAMA0
VM output → serial.sock → bridge stdout → PTY slave → TerminalSession → TerminalEmulator → TerminalView
Resize    → PTY TIOCSWINSZ → SIGWINCH → bridge → ctrl.sock → VM hvc0 daemon → stty → SIGWINCH in VM
Mouse     → TerminalView (touch/mouse) → sendMouseEventCode() → PTY → bridge → serial → VM
```

### CSI 6n / nvim Support
No CSI 6n filtering in the bridge. The shell (`set +o checkwinsize` in `/etc/profile`)
does not send cursor position queries. nvim's TUI initializes correctly.

### Boot Monitoring
`PodroidQemu` connects to `serial.sock` via `android.net.LocalSocket` and reads until
the "Ready" stage is detected (writing to `console.log`). `releaseSerial()` shuts
down the socket input/output and closes it so `podroid-bridge` can connect when
the user opens the terminal.

**Serial handoff timing**: QEMU serial socket (`server,nowait`) only serves one client
at a time. `releaseSerial()` uses `shutdownInput()` + `shutdownOutput()` + `close()`
to reliably interrupt the boot monitor's blocking read. `createSession()` sleeps 500ms
after `releaseSerial()` to let QEMU re-listen. The bridge binary also has a retry
loop (50 attempts × 200ms = 10s max) as defense in depth.

### Extra Keys
ESC, TAB, CTRL (sticky), ALT (sticky),
arrows, HOME, END, PGUP, PGDN, F1-F12, -, /, |

---

## init-podroid (VM init script)

### Phase 1 (Early init, before overlay)
- Mount proc/sys/dev
- Load virtio modules (virtio_pci, virtio_net, virtio_blk)
- Detect persistent storage device (vda/vdb)
- Mount ext4, create overlayfs (lower=/, upper=/mnt/persist/upper, work=/mnt/persist/work)
- `chroot /mnt/overlay /init --main`

### Phase 2 (--main flag)
- Mount filesystems (proc, sys, devtmpfs, devpts, tmpfs)
- busybox --install -s
- Network setup (SLIRP: 10.0.2.15/24, gateway 10.0.2.2, DNS 10.0.2.3)
- Downloads folder mount via 9p virtio
- Container config (sysctl, /etc/subuid, containers.conf, storage.conf, registries.conf, crun.conf)
- SSH setup (Dropbear): RSA host key via `dropbearkey`, password "podroid"
- MOTD with VM info
- Resize control daemon: background loop reads `RESIZE rows cols` from `/dev/hvc0`,
  calls `stty rows N cols M < /dev/ttyAMA0`
- `podroid-login` wrapper for getty
- getty on ttyAMA0

---

## Build Commands

```bash
# Build initramfs (requires Docker with multi-arch)
./docker-build-initramfs.sh

# Build QEMU + bridge (requires Docker)
./build-qemu-android.sh

# Build Termux terminal lib with 16KB page alignment (requires NDK)
./build-termux-android.sh

# Build APK
./gradlew assembleDebug

# Uninstall + install
adb uninstall com.excp.podroid.debug && adb install -r app/build/outputs/apk/debug/app-debug.apk
```

**Workflow**: If initramfs modified → `docker-build-initramfs.sh` → `gradlew assembleDebug` →
`adb uninstall com.excp.podroid.debug && adb install -r`. If only APK code modified → just the
last three steps.

---

## Permissions

| Permission | Purpose |
|------------|---------|
| INTERNET | SLIRP networking, QMP socket, GitHub API |
| ACCESS_NETWORK_STATE | Get device IP |
| WAKE_LOCK | Keep CPU awake during VM run |
| VIBRATE | Terminal bell feedback |
| FOREGROUND_SERVICE | Host QEMU process |
| FOREGROUND_SERVICE_SPECIAL_USE | QEMU "emulation" subtype |
| POST_NOTIFICATIONS | Android 13+ notification permission |
| MANAGE_EXTERNAL_STORAGE | Downloads folder sharing via virtio-9p |

---

## Design Patterns

- **Single Activity:** Compose Navigation with NavHost
- **Hilt:** Constructor injection, no @Provides needed
- **DataStore:** Preferences DataStore for all settings
- **StateFlow:** All UI state exposed as StateFlow
- **Reflection:** `TerminalView.mTermSession` and `mEmulator` set directly via reflection
- **Coroutines:** IO dispatcher for QEMU I/O, Main for UI updates
- **Sealed Classes:** VmState for lifecycle states
- **Persistence:** Two-layer (DataStore for config, ext4 image for VM data)

---

## Common Tasks Reference

### Add a new setting
1. Add key to `SettingsRepository.kt` (DataStore key + Flow)
2. Add UI in `SettingsScreen.kt` (Slider/Switch/etc.)
3. Add setter in `SettingsViewModel.kt`

### Add a new port forward preset
1. Add to `servicePresets` list in `SettingsScreen.kt`

### Add a new boot stage detection
1. Add case in `PodroidQemu.detectBootStage()`

### Modify QEMU arguments
1. Edit `PodroidQemu.buildCommand()`

### Modify the terminal bridge
1. Edit `podroid-bridge.c`
2. Rebuild with `./build-qemu-android.sh` (bridge is compiled alongside QEMU)

### Add a terminal color theme
1. Add `.properties` file to `app/src/main/assets/colors/`
2. Theme appears automatically in the Settings picker dialog

### Add a terminal font
1. Add `.ttf` file to `app/src/main/assets/fonts/`
2. Font appears automatically in the Settings picker dialog

### Add a new VM control signal (e.g. beyond RESIZE)
1. Add a new message format to `podroid-bridge.c` (write to ctrl.sock)
2. Add a new case to the resize daemon in `init-podroid` (read from `/dev/hvc0`)

### Modify VM init sequence
1. Edit `init-podroid` shell script

---

## Dependency Versions (gradle/libs.versions.toml)

| Library | Version |
|---------|---------|
| Android Gradle Plugin | 9.1.0 |
| Kotlin | 2.2.21 |
| KSP | 2.3.6 |
| Compose BOM | 2026.03.01 |
| Navigation Compose | 2.9.7 |
| Hilt | 2.59.2 |
| Coroutines | 1.9.0 |
| DataStore | 1.2.1 |
| Termux terminal | v0.118.1 |
| Lifecycle | 2.9.0 |
| Activity Compose | 1.10.1 |
| Room | 2.8.4 (declared but **NOT USED**) |

## Build Scripts

### docker-build-initramfs.sh
- Uses multi-stage Dockerfile
- Downloads Alpine 3.23 aarch64 netboot + virt ISO
- Installs packages via qemu-user-static on x86_64
- Outputs: `app/src/main/assets/{vmlinuz-virt,initrd.img}` (~71MB)

### build-qemu-android.sh
- Reads `podroidQemuVersion` from `gradle.properties`
- Builds via `Dockerfile.qemu`
- Compiles podroid-bridge alongside QEMU
- Outputs to `app/src/main/jniLibs/arm64-v8a/` and `app/src/main/assets/qemu/`

### build-termux-android.sh
- Clones Termux v0.118.1 from GitHub
- Compiles `libtermux.so` with **16KB page alignment** (`-Wl,-z,max-page-size=16384`)
- Verifies alignment via Python ELF parsing
- Required because Android may use 16KB page devices

## Dockerfile - VM Rootfs Packages

Installed via `apk` in Alpine aarch64:
```
linux-virt, busybox, ttyd, podman, netavark, aardvark-dns,
fuse-overlayfs, slirp4netns, shadow-uidmap, ca-certificates,
crun, curl, e2fsprogs, util-linux, openrc, dropbear,
ncurses-terminfo-base, musl-locales
```

## ProGuard Rules (app/proguard-rules.pro)

Keeps Termux reflection targets:
- `TerminalView.mTermSession` — set directly in TerminalScreen to wire the session
- `TerminalView.mEmulator` — set directly in TerminalScreen to wire the display emulator

## 16KB Page Alignment Requirement

All native libs (QEMU, slirp, termux.so) must have `p_align >= 16384` in ELF headers. This is verified by Python scripts in the build scripts. Reason: some Android devices use 16KB system pages.

## Terminal Resize Sequence

When the view layout changes (keyboard open/close, rotation):
1. `addOnLayoutChangeListener` calls `TerminalView.updateSize()` (uses renderer's exact font metrics)
2. TerminalView calculates cols/rows, calls `TerminalSession.updateSize(cols, rows)`
3. Termux JNI: `ioctl(pty_master_fd, TIOCSWINSZ)` → SIGWINCH to `podroid-bridge`
4. Bridge: `ioctl(STDIN, TIOCGWINSZ)` → writes `RESIZE rows cols\n` to `ctrl.sock`
5. VM daemon reads `/dev/hvc0` → `stty rows N cols M < /dev/ttyAMA0`
6. Linux kernel sends SIGWINCH to the foreground process group of ttyAMA0

No stdin injection. No debounce. nvim/htop/btop resize automatically.

## Settings.gradle.kts

- **Root project name:** `VirtuDroid`
- **App module:** `:app`
- **Repositories:** google, mavenCentral, **JitPack** (for Termux)
- **Toolchain:** foojay-resolver-convention 1.0.0

## Dockerfile.qemu - QEMU Build Details

### Build Dependencies
- NDK r27c (Android API 28) — QEMU builds against API 28 sysroot to avoid symbols not available in Android 9-11 (like copy_file_range)
- Cross-compile from debian:bookworm
- Dependencies: pcre2, libffi, glib 2.82.5, pixman 0.44.2, libattr, **libucontext**

### libucontext Shim
Android Bionic lacks proper `ucontext.h`. Built from [kaniini/libucontext](https://github.com/kaniini/libucontext). Provides `getcontext`, `swapcontext`, etc. A header shim remaps the standard functions to the libucontext versions.

### QEMU Patches Applied
1. **librt optional** — Bionic has librt functions in libc, not a separate library
2. **memfd_create shim** — `qemu_shm_alloc` uses `syscall(__NR_memfd_create)` instead of `shm_open` (API 28+)
3. **libattr stub** — `libattr = declare_dependency()` bypasses pkg-config detection (xattr in Bionic's libc)
4. **st_\*_nsec undef** — Undefines `st_atime_nsec` etc. macros from Android's sys/stat.h that clash with 9p struct fields

### QEMU Configure Flags
```
--enable-tcg --enable-kvm --enable-system --enable-slirp --enable-virtfs --enable-pie
--disable-werror --disable-docs --disable-gtk --disable-sdl --disable-opengl --disable-vnc
--disable-capstone --disable-seccomp --disable-libiscsi --disable-libnfs --disable-libssh
--disable-curl --disable-spice --disable-vde --disable-linux-io-uring
--with-coroutine=ucontext  ← REQUIRED: PAC fix for Android 15+ on Tensor devices
```

### podroid-bridge Build (in Dockerfile.qemu)
```
${CC} ${CFLAGS} -fPIE -pie ${LDFLAGS} podroid-bridge.c -o podroid-bridge
cp podroid-bridge libpodroid-bridge.so
```
Same NDK, same CFLAGS/LDFLAGS (16KB page alignment enforced). Outputs `libpodroid-bridge.so`.

### podroid-bridge Responsibilities
- Connect to `serial.sock` (QEMU serial unix socket)
- Allocate PTY via `forkpty()` (Termux provides the PTY master)
- Set `cfmakeraw()` on PTY slave (VM's getty handles echo/line editing)
- Relay bidirectionally: PTY ↔ serial.sock
- No filtering — all escape sequences pass through unmodified
- On SIGWINCH: read PTY size via `TIOCGWINSZ`, write `RESIZE rows cols\n` to `ctrl.sock`

### PAC (Pointer Authentication) Fix
Android 15+ on Pixel (Tensor) uses ARMv9.2 with PAC. Default QEMU sigaltstack coroutine calls `siglongjmp` across alternate signal stacks — this fails PAC validation and raises SIGILL. Fix: `--with-coroutine=ucontext` uses `swapcontext` instead.

### Output Artifacts
```
app/src/main/jniLibs/arm64-v8a/
├── libqemu-system-aarch64.so  # QEMU binary renamed to .so for APK packaging
├── libslirp.so                # SLIRP networking (soname patched from .so.0)
└── libpodroid-bridge.so       # Terminal bridge binary renamed to .so for APK packaging

app/src/main/assets/qemu/
├── efi-virtio.rom             # VirtIO EFI ROM
└── keymaps/                   # QEMU keyboard maps
```

### 16KB Page Alignment
All artifacts must have `p_align >= 16384`. Enforced via `LDFLAGS=-Wl,-z,max-page-size=16384` in both NDK toolchain and QEMU link step. Verified by Python ELF parser in build script.

## test-deploy.sh

Automated build → install → boot test script:
1. Check ADB device connected
2. Optionally rebuild initramfs
3. Build debug APK with Gradle
4. Install via `adb install -r`
5. Force stop app + delete `storage.img` (clean state)
6. Launch app via `am start`
7. Wait for user to press "Start VM"
8. Poll `console.log` for boot completion (timeout 60s)
9. Run validation checks (banner, IP, persistent, internet, Ready!, kernel modules, network)
10. Detect kernel panics/crashes

## Known Quirks

- **Room declared but unused** — DataStore is used for persistence instead
- **KSP version differs from Kotlin** — Kotlin 2.2.21, KSP 2.3.6
- **QEMU packaged as `.so`** — Actually an ELF executable renamed from binary to `.so` for Android packaging
- **podroid-bridge packaged as `.so`** — Same trick: PIE executable renamed to `.so` for APK extraction
- **Project name in Gradle is "VirtuDroid"** — `rootProject.name` in settings.gradle.kts
- **SLIRP soname patched** — `libslirp.so.0` → `libslirp.so` via patchelf for APK compatibility
- **libtermux.so custom-built** — Termux's prebuilt uses 4KB pages; rebuilt with 16KB alignment via `build-termux-android.sh`
- **Boot monitor connects to serial.sock first** — PodroidQemu reads boot output from serial.sock until "Ready", then `releaseSerial()` uses `shutdownInput()`+`close()` to interrupt blocking read; bridge has 50-retry connect loop as defense in depth
- **releaseSerial() sets bootStage="Ready"** — With persistent overlay the VM boots faster than the boot monitor can detect boot completion strings; `releaseSerial()` unconditionally marks boot as "Ready" when the terminal takes over
- **nvim hangs on Podroid** — `nvim --headless` hangs even without terminal I/O; likely a LuaJIT runtime issue on Alpine ARM64. nvim works fine over SSH (Dropbear).

## GitHub

- **Repo:** github.com/ExTV/Podroid
- **Releases API:** https://api.github.com/repos/ExTV/Podroid/releases/latest
- **Update check:** `UpdateRepository.checkForUpdate()` compares tag_name vs BuildConfig.VERSION_NAME

---

## TODO (Future Work)

### High Priority
- [ ] **nvim hangs on Podroid** — `nvim` (and `nvim --headless`) hangs silently when run in the Podroid terminal. Works over SSH. Likely a LuaJIT or process initialization issue specific to the Podroid VM environment.
- [ ] **Container Hub** — SSH-based container management via JSch. Pre-built service catalog (Pi-hole, Vaultwarden, code-server, Gitea, Jellyfin, Uptime Kuma, Filebrowser, Nginx, Grafana). Live `podman ps` polling.
- [ ] **Docker socket compatibility** (#6) — `rc-service` and `docker` commands don't work. The custom init-podroid lacks proper OpenRC integration. Needs proper service scripts for Podman to work as expected.
- [ ] **ARM native Debian base** (#4) — Option to use Debian ARM as VM base instead of Alpine. Large scope, separate base OS layer.

### Known Issues (Investigation Notes)

#### Issue #10: QEMU copy_file_range on Android 12 (FIXED)

**Problem:** QEMU crashes on devices with Android 12 (OnePlus 7 Pro, kernel 5.x) with error:
```
cannot locate symbol "copy_file_range" referenced by libqemu-system-aarch64.so
```

**Root Cause:**
- Built against Android 34 (API 34) sysroot
- glibc in API 34 has `copy_file_range` symbol, but Android 12's Bionic doesn't
- Linker fails to resolve the symbol at runtime

**Fix Applied (v1.1.1):**
1. Changed `app/build.gradle.kts`: minSdk 26 → 28, targetSdk 36
2. Changed `Dockerfile.qemu`: API 34 → 28
3. QEMU now builds against Android 28 sysroot which doesn't have `copy_file_range`
4. Uses only Bionic functions available in API 28

**Note:** This raises the minimum Android version from 8.0 (API 26) to 9.0 (API 28).

---

### Moderate Priority
- [ ] **Initramfs size reduction** — The ~71MB initramfs could be trimmed (podman-remote, GPG tools, busybox-extras, iptables, traceroute are unused). Kernel module stripping could reduce from 883 to ~8 modules. Caution: previous attempt broke ext4 mount and network — kernel/module version mismatch caused virtio modules to fail loading.
- [ ] **Overlay mount validation** — init-podroid falls back silently if overlayfs mount fails. Detect and warn user with actionable error.
- [ ] **DNS configurable** — Currently hardcoded 8.8.8.8 and 1.1.1.1. Add gateway DNS (10.0.2.3) as primary with fallback options. Per-VM DNS config in Settings.
- [ ] **OpenRC integration** — Replace manual `init-podroid` boot with proper OpenRC service scripts. This would also fix the `rc-service` and `docker` issues.

### Low Priority / Nice-to-Have
- [ ] **Terminal title → TopAppBar** — `onTitleChanged()` in `TerminalSessionClient` is a no-op. Could update the app bar title when the shell sets the terminal title via OSC sequences.
- [ ] **APK size reduction** — Currently ~29MB of fonts and full QEMU. Options: deferred font download, split APK, font subsetting, strip debug symbols.
- [ ] **Custom font loading** — Issue #5 (Allow loading custom fonts). Allow users to load their own `.ttf`/`.otf` fonts in addition to the built-in collection.
