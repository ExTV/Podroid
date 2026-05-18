<div align="center">

<img src="https://github.com/ExTV.png" width="96" height="96" alt="Podroid" style="border-radius: 24px" />

# Podroid

**Linux containers and a Linux desktop on Android. No root.**

A real Alpine VM, a real Linux kernel, rootless Podman, and an in-app X11 viewer, all in one APK.

<p>
  <a href="https://github.com/ExTV/Podroid/releases"><img src="https://img.shields.io/github/v/release/ExTV/Podroid?include_prereleases&style=flat-square&label=release&color=blue" alt="Release" /></a>
  <a href="https://github.com/ExTV/Podroid/releases"><img src="https://img.shields.io/github/downloads/ExTV/Podroid/total?style=flat-square&color=brightgreen" alt="Downloads" /></a>
  <a href="https://github.com/ExTV/Podroid/stargazers"><img src="https://img.shields.io/github/stars/ExTV/Podroid?style=flat-square&color=yellow" alt="Stars" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/github/license/ExTV/Podroid?style=flat-square" alt="License" /></a>
  <img src="https://img.shields.io/badge/platform-Android%209%2B-3DDC84?style=flat-square&logo=android&logoColor=white" alt="Android 9+" />
  <img src="https://img.shields.io/badge/arch-arm64-orange?style=flat-square" alt="arm64" />
</p>

<a href="https://extv.github.io/Podroid/"><strong>Website</strong></a> ·
<a href="https://github.com/ExTV/Podroid/releases/latest"><strong>Download</strong></a> ·
<a href="#quick-start"><strong>Quick Start</strong></a> ·
<a href="#build"><strong>Build</strong></a>

</div>

---

## What it is

Podroid boots a standard Alpine 3.23 VM under QEMU, with a custom Linux **7.0.5** kernel that has every option real containers need (overlayfs, netfilter, bridge, FUSE, binfmt_misc, …) compiled in. You get rootless Podman, Docker and LXC out of the box, plus an in-app X11 desktop for GUI apps, without root, without ADB tricks, on stock Android 9+.

## Quick start

1. Grab the APK from [Releases](https://github.com/ExTV/Podroid/releases/latest).
2. Open Podroid → **Start VM** (boots in 6–30 s).
3. Tap **Open Terminal** when status turns *Ready*.

```bash
# Containers
podman run --rm alpine echo hello
podman run -d -p 8080:80 nginx          # reachable from Android at 127.0.0.1:8080

# GUI apps: tap the monitor icon in the terminal to open the X11 viewer
apk add firefox
firefox &
```

Default login: **root / podroid**.

## Features

- **Linux 7.0.5 kernel**, custom-built. Every container option compiled `=y`; the build fails if any get demoted.
- **Podman, Docker and LXC pre-installed.** Rootless Podman wired up with crun + netavark + slirp4netns; `rc-service docker start` or `lxc-create …` work out of the box.
- **OpenRC as PID 1.** `apk add` whatever you want, `rc-service ... start`, and it persists across reboots.
- **In-app X11 viewer** (Xvnc + PulseAudio) with touch→mouse, soft-keyboard input, and PCM audio over loopback.
- **Built-in terminal** powered by the Termux engine: xterm-256color, mouse tracking, debounced resize, customizable extra-keys row.
- **Persistent ext4 overlay** on a read-only Alpine squashfs. Your installs and configs survive every reboot.
- **Adaptive Material 3 UI** for phone, tablet and landscape, with dynamic color and a foreground service that keeps the VM alive.

## Themes & fonts

Open the terminal's **Quick Settings** (top sheet) for both pickers.

- **122 bundled color themes:** Dracula, Nord, Solarized, Tokyo Night, Catppuccin, Gruvbox, Monokai, the full base16 family, and more.
- **13 bundled monospace fonts:** JetBrains Mono, Fira Code, Cascadia Code, Source Code Pro, Hack, Iosevka, Victor Mono, Monofur, Anonymous Pro, DejaVu Sans Mono, Liberation Mono, Ubuntu Mono, Terminus.
- **Bring your own font.** The Fonts picker has a **+ Add** chip; pick any `.ttf` from the system file chooser and it's instantly available. Long-press a custom font to remove it.
- **Bring your own theme.** The Color-themes picker has an **Import** chip that accepts a URL to a Gogh-style `.properties` file. Long-press to remove.
- Font size, color theme, font, dark/light and haptics: all change live, no VM restart.

## VM is fully configurable

Everything below is editable from **Settings**:

- **Memory:** 512 MB · 1 GB · 2 GB · 4 GB
- **CPU cores:** 1 · 2 · 4 · 6 · 8
- **Persistent storage:** 2 GB to 64 GB (chosen at first setup; reset to change)
- **Downloads folder sharing** (virtio-9p): toggle on/off; mounted inside the VM at `/mnt/downloads`
- **SSH** (Dropbear on `127.0.0.1:9922`): toggle on/off
- **Port forwards:** add/remove host ↔ guest TCP/UDP rules live, no VM restart (sent to QEMU over QMP)
- **Advanced QEMU args:** full `-cpu` / `-accel` / RNG / device line, editable
- **Advanced kernel cmdline:** extras appended to the boot cmdline
- **Full App Reset:** wipe the persistent storage image and start over

RAM and CPU changes take effect on the next start; everything else is hot.

## Diagnostics

**Settings → Export Diagnostic Log** bundles app info, current settings, VM state, app logcat and the full QEMU console output into a single `log.txt` and shares it via the standard Android share sheet. Attach this to bug reports; it's almost always enough to diagnose a boot or container issue without ADB.

## Troubleshooting

### "QEMU crashed (SIGKILL)" / VM dies when I switch to another app

On Android 12+ the system aggressively reaps **phantom processes** — any native subprocess (here, QEMU + the bridge) spawned by an app that isn't visible as a standard Android process. Even with our foreground service keeping the app alive, the phantom children do not inherit that importance and get killed when the parent app goes to the background or under memory pressure.

Symptoms:
- VM was running, you switched to another app for a minute, came back to "VM stopped" or `Error(QEMU crashed (SIGKILL))`.
- Diagnostic log shows `QEMU exited: 137` and `Process PhantomProcessRecord {... libqemu-system-aarch64.so/...} died`.

Disable the phantom-process killer with **one** of the following.

**Via ADB** (PC connected to the phone):

```bash
adb shell "/system/bin/device_config set_sync_disabled_for_tests persistent"
adb shell "/system/bin/device_config put activity_manager max_phantom_processes 2147483647"
adb shell settings put global settings_enable_monitor_phantom_procs false
```

**Via root** (Termux or any on-device terminal emulator with `su`):

```bash
su -c /system/bin/device_config set_sync_disabled_for_tests persistent
su -c /system/bin/device_config put activity_manager max_phantom_processes 2147483647
su -c setprop persist.sys.fflag.override.settings_enable_monitor_phantom_procs false
```

Either approach persists across reboots. The change affects only apps that spawn native children (Podroid, Termux proot, etc.); standard apps are unaffected.

## Requirements

| | |
|---|---|
| Architecture | ARM64 only (`aarch64`) |
| OS           | Android 9.0+ (API 28), targets API 36 |
| Storage      | ~200 MB app + chosen VM disk (default 2 GB, max 64 GB) |
| Memory       | 2 GB device RAM recommended (VM defaults to 512 MB) |

## Build

```bash
git clone https://github.com/ExTV/Podroid.git && cd Podroid

./build-all.sh all          # kernel + initramfs + rootfs + qemu + termux + APK
./build-all.sh deploy       # the above, plus install + launch
./build-all.sh test         # boot validation: deploys APK, polls console.log for "Ready!"
```

Individual stages: `kernel`, `initramfs`, `rootfs`, `qemu`, `termux`, `apk`. All container builds are Docker-cached.

**Prereqs:** Docker 20.10+, Android NDK r27c, Android SDK with platform 36 + build-tools.

## Contributing

Contributions of every size are welcome: bug reports, kernel-config tweaks, new themes, UI polish, X11 input fixes, anything.

- **Pull requests:** read [CONTRIBUTING.md](CONTRIBUTING.md) first. Keep changes scoped, run `./build-all.sh test` before pushing, and explain *why* in the PR description.
- **Bug reports:** [open an issue](https://github.com/ExTV/Podroid/issues/new) with your device + Android version, a short repro, and the diagnostic log (**Settings → Export Diagnostic Log** in the app).
- **Diving into the engine:** [`skill.md`](skill.md) is the deep map: boot pipeline, every native binary, every quirk, every common task. AI assistants should read it before touching anything.

## Credits

| | |
|---|---|
| [QEMU](https://www.qemu.org)                   | Machine emulation |
| [Termux](https://github.com/termux/termux-app) | Terminal emulator engine |

Full list (Linux, Alpine, Podman, TigerVNC, PulseAudio, Limbo and more) in [CREDITS.md](CREDITS.md).

## License

[GNU General Public License v2.0](LICENSE). QEMU, Linux, Alpine, Podman and the rest are distributed under their respective upstream licenses.
