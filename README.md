# Podroid — Rootless Podman for Android

Run Linux containers on your Android phone. Podroid launches a lightweight Alpine Linux VM via QEMU and gives you a fully working **Podman** container runtime, accessible through a built-in serial terminal.

## Features

- **Podman** — pull and run OCI containers (`podman run alpine echo hello`)
- **Alpine Linux 3.23** — minimal aarch64 VM with networking
- **Internet access** — QEMU user-mode networking (SLIRP), DNS via 8.8.8.8
- **Persistent storage** — 2GB ext4 disk for `/var/lib/containers`
- **Built-in terminal** — serial console UI, no browser needed
- **One-tap start/stop** — simple Material 3 interface

## Requirements

- Android device with **arm64** (aarch64) CPU
- Android **14+** (API 34)
- ~200MB storage for the app

## Architecture

```
┌──────────────────────────────────────┐
│            Podroid App               │
│                                      │
│   Home Screen ──► Terminal Screen    │
│       │              (serial I/O)    │
│       ▼                              │
│   PodroidQemu (process manager)      │
│       │                              │
│   libqemu-system-aarch64.so          │
│       │                              │
│   ┌───▼──────────────────────────┐   │
│   │  Alpine Linux VM (initramfs) │   │
│   │  - Podman + crun             │   │
│   │  - fuse-overlayfs            │   │
│   │  - netavark networking       │   │
│   │  - virtio-net (SLIRP)        │   │
│   │  - virtio-blk (storage)      │   │
│   └──────────────────────────────┘   │
└──────────────────────────────────────┘
```

## Project Structure

```
├── Dockerfile                  # Multi-stage initramfs builder
├── docker-build-initramfs.sh   # Build script (produces vmlinuz-virt + initrd.img)
├── init-podroid                # Custom /init for the Alpine VM
├── app/
│   └── src/main/
│       ├── java/com/excp/podroid/
│       │   ├── PodroidApplication.kt   # Asset extraction on startup
│       │   ├── MainActivity.kt         # Single-activity Compose host
│       │   ├── engine/
│       │   │   ├── PodroidQemu.kt      # QEMU lifecycle + serial I/O
│       │   │   └── VmState.kt          # VM state machine
│       │   ├── service/
│       │   │   └── PodroidService.kt   # Foreground service
│       │   └── ui/screens/
│       │       ├── home/               # Start/Stop + status
│       │       ├── terminal/           # Serial console UI
│       │       └── settings/           # App settings
│       ├── jniLibs/arm64-v8a/
│       │   ├── libqemu-system-aarch64.so   # Pre-built QEMU
│       │   └── libslirp.so                 # Network library
│       └── assets/
│           ├── vmlinuz-virt    # Alpine kernel (generated)
│           ├── initrd.img      # Alpine initramfs (generated)
│           └── qemu/           # QEMU firmware files
```

## Building

### 1. Build the initramfs

Requires Docker with multi-arch support (`docker buildx` or `qemu-user-static`):

```bash
./docker-build-initramfs.sh
```

This produces `app/src/main/assets/vmlinuz-virt` and `app/src/main/assets/initrd.img`.

### 2. Build the APK

```bash
./gradlew assembleDebug
```

### 3. Install

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Usage

1. Open Podroid
2. Tap **Start Podman**
3. Wait for the VM to boot (~20s)
4. Tap **Open Terminal**
5. Run containers:
   ```
   podman run --rm alpine echo hello
   podman run --rm -it alpine sh
   ```

> **Tip:** Use `ping`, `wget`, or `curl` to test connectivity.

## How It Works

Podroid uses QEMU system emulation (TCG) to run a headless aarch64 Alpine Linux VM inside an Android app process. The VM boots from an initramfs containing a full Alpine rootfs with Podman, crun, fuse-overlayfs, and netavark pre-installed.

Communication happens via QEMU's serial console (`-serial stdio`), which maps to the Android process's stdin/stdout. The app reads stdout in a background thread and accumulates it in a StateFlow for the Compose terminal UI.

Key QEMU flags:
```
-M virt -cpu max -m 1024 -accel tcg,thread=multi
-kernel vmlinuz-virt -initrd initrd.img
-append "console=ttyAMA0 loglevel=1 quiet"
-netdev user,id=net0 -device virtio-net,netdev=net0
-serial stdio -display none
```

## Credits

- [QEMU](https://www.qemu.org) — machine emulation
- [Alpine Linux](https://alpinelinux.org) — lightweight VM base
- [Podman](https://podman.io) — container runtime
- [Limbo PC Emulator](https://github.com/limboemu/limbo) — pioneered QEMU on Android

## License

GNU General Public License v2.0
