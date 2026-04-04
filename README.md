# Podroid

**Run Linux containers on Android — no root required.**

Podroid spins up a lightweight Alpine Linux VM on your phone using QEMU and gives you a fully working [Podman](https://podman.io) container runtime with a built-in serial terminal.

<p>
  <img src="screenshots/1.png" width="24%" />
  <img src="screenshots/2.png" width="24%" />
  <img src="screenshots/3.png" width="24%" />
  <img src="screenshots/4.png" width="24%" />
</p>

## Highlights

| | |
|---|---|
| **Containers** | Pull and run any OCI image — `podman run --rm -it alpine sh` |
| **Terminal** | Full xterm emulation with Ctrl, Alt, F1-F12, arrows, and more |
| **Persistence** | Packages, configs, and container images survive restarts |
| **Networking** | Internet access out of the box, port forwarding to Android host |
| **Self-contained** | No root, no Termux, no host binaries — just install the APK |

## Requirements

- **arm64** Android device
- Android **8.0+** (API 26)
- ~150 MB free storage

## Quick Start

1. Install the APK from [Releases](https://github.com/ExTV/Podroid/releases)
2. Open Podroid and tap **Start Podman**
3. Wait for boot (~20 s) — progress is shown on screen and in the notification
4. Tap **Open Terminal**
5. Run containers:

```sh
podman run --rm alpine echo hello
podman run --rm -it alpine sh
podman run -d -p 8080:80 nginx
```

## Terminal

The terminal is powered by [Termux](https://github.com/termux/termux-app)'s `TerminalView` with full VT100/xterm emulation wired directly to the VM's serial console.

**Extra keys bar** (scrollable):

`ESC` `TAB` `SYNC` `CTRL` `ALT` `arrows` `HOME` `END` `PGUP` `PGDN` `F1–F12` `-` `/` `|`

- **CTRL / ALT** are sticky toggles — tap once, then press a letter
- **SYNC** manually pushes the terminal dimensions to the VM
- Terminal size auto-syncs on keyboard open/close so TUI apps (vim, btop, htop) render correctly
- Bell character triggers haptic feedback

## Port Forwarding

Forward ports from the VM to your Android device:

1. Go to **Settings**
2. Add a rule (e.g. TCP 8080 -> 80)
3. Access the service at `localhost:8080` on your phone

Rules persist across restarts and can be added or removed while the VM is running.

## How It Works

```
Android App
├── Foreground Service (keeps VM alive)
├── PodroidQemu
│   ├── libqemu-system-aarch64.so  (QEMU TCG, no KVM)
│   ├── Serial stdio ←→ TerminalEmulator
│   └── QMP socket (port forwarding, VM control)
└── Alpine Linux VM
    ├── initramfs (read-only base layer)
    ├── ext4 disk (persistent overlay)
    ├── getty on ttyAMA0 (job control)
    └── Podman + crun + netavark + slirp4netns
```

**Boot sequence:** QEMU loads `vmlinuz-virt` + `initrd.img`. A two-phase init (`init-podroid`) mounts a persistent ext4 disk as an overlayfs upper layer over the initramfs. Packages you install and containers you pull are written to the overlay and survive reboots.

**Terminal wiring:** The app cannot fork host processes, so `TerminalSession` is wired to QEMU's serial I/O via reflection — keyboard input goes to QEMU stdin, QEMU stdout feeds the terminal emulator. Terminal dimensions are synced to the VM via `stty` so TUI apps see the correct size.

**Networking:** QEMU user-mode networking (SLIRP) puts the VM at `10.0.2.15`. Port forwarding uses QEMU's `hostfwd`, managed at startup via CLI args and at runtime via QMP.

## Building from Source

### 1. Build the initramfs

Requires Docker with multi-arch support:

```sh
./docker-build-initramfs.sh
```

### 2. Build the APK

```sh
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Project Layout

```
Dockerfile                  # Multi-stage initramfs builder (Alpine aarch64)
docker-build-initramfs.sh   # One-command build script
init-podroid                # Custom /init for the VM

app/src/main/
├── java/com/excp/podroid/
│   ├── engine/             # QEMU lifecycle, QMP client, VM state machine
│   ├── service/            # Foreground service with boot-stage notifications
│   ├── data/repository/    # Settings + port forward persistence
│   └── ui/screens/         # Home, Terminal, Settings (Jetpack Compose)
├── jniLibs/arm64-v8a/      # Pre-built QEMU + libslirp
└── assets/                 # Kernel + initramfs (generated)
```

## Credits

- [QEMU](https://www.qemu.org) — machine emulation
- [Alpine Linux](https://alpinelinux.org) — VM base
- [Podman](https://podman.io) — container runtime
- [Termux](https://github.com/termux/termux-app) — terminal emulator libraries
- [Limbo PC Emulator](https://github.com/limboemu/limbo) — pioneered QEMU on Android

## License

GNU General Public License v2.0
