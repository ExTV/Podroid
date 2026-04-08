# Podroid

<p align="center">
  <a href="https://github.com/ExTV/Podroid/releases">
    <img src="https://img.shields.io/github/v/release/ExTV/Podroid?include_prereleases&label=Latest%20Release" alt="Latest Release">
  </a>
  <a href="https://github.com/ExTV/Podroid/blob/main/LICENSE">
    <img src="https://img.shields.io/github/license/ExTV/Podroid?color=blue" alt="License">
  </a>
  <a href="https://github.com/ExTV/Podroid">
    <img src="https://img.shields.io/github/last-commit/ExTV/Podroid/main?color=green" alt="Last Commit">
  </a>
</p>

**Run Linux containers on your Android phone — no root required.**

Podroid spins up a lightweight Alpine Linux VM using QEMU and gives you a fully working [Podman](https://podman.io) container runtime with a built-in terminal. Install the APK, tap Start, and you're running containers in under a minute.

---

## Quick Start

1. **Install** the APK from [Releases](https://github.com/ExTV/Podroid/releases)
2. **Open** Podroid and tap **Start VM**
3. **Wait** ~20 seconds — boot progress shows in the notification
4. **Tap** **Open Terminal**
5. **Run** containers:

```sh
podman run --rm alpine echo hello
podman run --rm -it alpine sh
podman run -d -p 8080:80 nginx
```

---

## Features

### Container Runtime
- **Podman** — rootless container runtime with full Docker compatibility
- **Persistence** — packages, configs, and images survive restarts via overlayfs
- **Internet access** — out of the box via QEMU SLIRP networking

### Terminal
- **Full VT100/xterm** — via Termux TerminalView
- **Real PTY** — proper job control, signals, and escape sequences
- **114 color themes** — Dracula, Nord, Solarized, Tokyo Night, Catppuccin, Gruvbox, and 108 more
- **13 fonts** — JetBrains Mono, Fira Code, Cascadia Code, Source Code Pro, Hack, and more
- **Mouse support** — full CSI mouse tracking for btop, htop, mc, vim
- **Extra keys** — ESC, TAB, CTRL, ALT (sticky), arrows, F1–F12, and common symbols
- **Auto-resize** — vim, btop, nano update on keyboard open/close

### Networking
- **Port forwarding** — expose VM services to your Android device
- **Runtime control** — add/remove forwards while VM runs via QMP
- **Service presets** — one-tap setup for Pi-hole, Nginx, Gitea, Grafana
- **SSH** — built-in Dropbear on port 9922

### Storage
- **2–64 GB** — configurable persistent storage
- **Downloads sharing** — mount Android Downloads via virtio-9p

### Performance
- **ARM64-native** — runs on your device's CPU via QEMU TCG
- **Multi-core** — configurable CPU count
- **512 MB+** — allocatable RAM (default)

---

## Requirements

| | |
|---|---|
| **Device** | arm64 Android (most phones from 2018+) |
| **Android** | 8.0+ (API 26) |
| **Storage** | ~150 MB app + VM disk size |

---

## How It Works

```
┌─────────────────────────────────────────────────────────────┐
│                    Android Device                           │
│                                                             │
│  ┌──────────────┐    ┌──────────────────────────────────┐  │
│  │ Podroid App │    │    Foreground Service            │  │
│  │              │    │    (keeps VM alive)              │  │
│  └──────┬───────┘    └───────────────┬───────────────────┘  │
│         │                            │                       │
│         ▼                            ▼                       │
│  ┌─────────────────────────────────────────────────────────┐│
│  │                   QEMU TCG VM                          ││
│  │  ┌──────────┐  ┌───────────┐  ┌──────────────────┐   ││
│  │  │ Kernel   │  │ Initramfs │  │ ext4 disk         │   ││
│  │  │vmlinuz   │  │ (Alpine)  │  │ (overlayfs)       │   ││
│  │  └──────────┘  └───────────┘  └──────────────────────┘   ││
│  │                                                      ││
│  │  ┌──────────┐  ┌──────────┐  ┌──────────────────┐   ││
│  │  │ Podman   │  │ Dropbear │  │ SLIRP            │   ││
│  │  │          │  │ SSH :22  │  │ Net              │   ││
│  │  └──────────┘  └──────────┘  └──────────────────┘   ││
│  └──────────────────────────────────────────────────────┘│
│         │                                                   │
│         ▼                                                   │
│  ┌───────────────────────────────────────────────────────┐│
│  │            Terminal (Termux TerminalView)             ││
│  │      PTY ↔ podroid-bridge ↔ serial socket             ││
│  └───────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────┘
```

**Boot Sequence:**
1. QEMU loads Linux kernel + initramfs
2. Phase 1 init mounts persistent ext4 as overlayfs
3. Phase 2 configures networking, Podman, SSH, getty
4. Terminal connects to serial console

**Networking:** QEMU user-mode networking (SLIRP) gives VM `10.0.2.15`. Port forwards via `-netdev hostfwd` at startup, QMP at runtime.

---

## Building from Source

```sh
# 1. Build VM initramfs and kernel (requires Docker)
./docker-build-initramfs.sh

# 2. Build QEMU + terminal bridge (requires Docker)
./build-qemu-android.sh

# 3. Build the APK
./gradlew assembleDebug
```

**Install:**
```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## AI/LLM Integration

Give AI assistants full context on Podroid with `skill.md`:

```sh
# OpenCode
/load_skill path/to/skill.md

# Claude/Cline
Read the skill.md file at: /path/to/Podroid/skill.md
```

The skill file includes complete architecture, all source files, build commands, known issues, and settings.

---

## Credits

- [QEMU](https://www.qemu.org) — machine emulation
- [Alpine Linux](https://alpinelinux.org) — VM base
- [Podman](https://podman.io) — container runtime
- [Termux](https://github.com/termux/termux-app) — terminal emulator
- [Limbo PC Emulator](https://github.com/limboemu/limbo) — QEMU on Android groundwork

---

## License

[GNU General Public License v2.0](LICENSE)