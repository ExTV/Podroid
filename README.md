<div align="center">

<img src="docs/logo.png" alt="Podroid logo" width="140" />

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
<a href="https://extv.github.io/Podroid/guide/"><strong>Documentation</strong></a> ·
<a href="https://github.com/ExTV/Podroid/releases/latest"><strong>Download</strong></a> ·
<a href="#quick-start"><strong>Quick Start</strong></a> ·
<a href="#build"><strong>Build</strong></a> ·
<a href="CONTRIBUTING.md"><strong>Contributing</strong></a>

</div>

---

## What it is

- Boots a standard **Alpine 3.23** VM under QEMU on stock Android 9+.
- Custom **Linux 7.0.5** kernel: every container option (overlayfs, netfilter, bridge, FUSE, binfmt_misc) compiled `=y`. Build fails if any get demoted.
- Rootless **Podman, Docker and LXC** pre-installed and pre-wired.
- In-app **X11 viewer** (Xvnc + PulseAudio) with touch→mouse and audio.
- No root, no userland tarballs. The default QEMU path needs no ADB; AVF acceleration on pKVM phones takes one ADB grant on first install.

## Quick start

1. Grab the APK from [Releases](https://github.com/ExTV/Podroid/releases/latest).
2. Open Podroid → **Start VM** (boots in 6–30 s).
3. Tap **Open Terminal** when status turns *Ready*.

```bash
# Containers
podman run --rm alpine echo hello
podman run -d -p 8080:80 nginx           # reachable from Android at 127.0.0.1:8080

# GUI apps: tap the monitor icon in the terminal to open the X11 viewer
apk add firefox
firefox &
```

Default login: **root / podroid**.

## Screenshots

<div align="center">
  <table>
    <tr>
      <td align="center"><img src="docs/screenshots/01-home-idle.png"        alt="Home screen with the VM stopped" width="240" /></td>
      <td align="center"><img src="docs/screenshots/02-home-running.png"     alt="Home screen with the VM running and network info visible" width="240" /></td>
      <td align="center"><img src="docs/screenshots/03-terminal-fastfetch.png" alt="Built-in terminal with fastfetch showing the Podroid logo and Alpine system info" width="240" /></td>
      <td align="center"><img src="docs/screenshots/04-quick-settings.png"   alt="Terminal Quick Settings sheet showing font size, theme, font, extra-keys and haptics toggles" width="240" /></td>
    </tr>
    <tr>
      <td align="center"><sub>Idle: VM stopped</sub></td>
      <td align="center"><sub>Running: IP, SSH, uptime</sub></td>
      <td align="center"><sub><code>fastfetch</code> with the Podroid logo inline</sub></td>
      <td align="center"><sub>Quick Settings: theme &amp; font</sub></td>
    </tr>
  </table>
</div>

## Features

- **Linux 7.0.5 kernel**, custom-built. Every container option compiled `=y`; the build fails if any get demoted.
- **Podman, Docker and LXC pre-installed.** Rootless Podman wired up with crun + netavark + slirp4netns; `rc-service docker start` runs the Docker daemon, and `lxc-create -t download` pulls full distro containers (Alpine, Ubuntu, Debian, …) out of the box.
- **OpenRC as PID 1.** `apk add` whatever you want, `rc-service ... start`, and it persists across reboots.
- **In-app X11 viewer** (Xvnc + PulseAudio): live-resizable display (match-device, or 720p–1440p / custom presets), direct-touch and trackpad pointer modes with scroll, fullscreen, rotation lock, external-keyboard and mouse-wheel support, soft-keyboard input, and PCM audio over loopback.
- **USB device passthrough.** Hot-plug a real USB device — Wi-Fi adapter, storage, serial adapter, audio interface — into the running VM straight from the Android USB stack. No root and no XML device filter: each device asks for permission when attached and is streamed to QEMU over QMP while the app runs.
- **Built-in terminal** powered by the Termux engine: xterm-256color, mouse tracking, debounced resize, customizable extra-keys row.
- **Persistent ext4 overlay** on a read-only Alpine squashfs. Installs and configs survive every reboot.
- **Adaptive Material 3 UI** for phone, tablet and landscape, with dynamic color and a foreground service that keeps the VM alive.

> **Known limitation (X11 viewer):** with an external mouse, right-click currently exits fullscreen instead of reaching the desktop — Android maps a mouse right-click to the Back action. Two-finger right-click via touch works. A fix is planned.

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
- **Downloads folder sharing:** toggle on/off; mounted inside the VM at `/mnt/downloads` (virtio-9p on QEMU, AVF `SharedPath` where the backend supports it)
- **USB passthrough:** opt-in toggle in Settings and at first-run setup (alongside Downloads sharing); hot-plugs attached USB devices into the running VM (QEMU backend only — adds a USB controller at boot, so set it while the VM is stopped)
- **SSH** (Dropbear on host port `9922`): toggle on/off; reachable from the LAN as `ssh root@<phone-ip> -p 9922` once the VM is Ready
- **Port forwards:** add/remove host ↔ guest TCP/UDP rules live, no VM restart
- **Advanced QEMU args:** full `-cpu` / `-accel` / RNG / device line, editable
- **Advanced kernel cmdline:** extras appended to the boot cmdline
- **Full App Reset:** wipe the persistent storage image and start over

RAM, CPU and backend changes take effect on the next start; everything else is hot.

## Diagnostics

**Settings → Export Diagnostic Log** bundles app info, current settings, VM state, app logcat and the full guest console (QEMU serial or AVF console, whichever backend is active) into a single `log.txt` and shares it via the standard Android share sheet. Attach this to bug reports; it's almost always enough to diagnose a boot or container issue without ADB.

**Settings → About → AVF (pKVM) diagnostic** runs a six-line probe of the Android Virtualization Framework on your device — feature flag, permissions, system service reachability — and a smoke-start of a minimal VM to confirm the path actually works end-to-end. Tap it before opening an issue about KVM not engaging.

## Hardware acceleration (KVM / pKVM)

By default Podroid emulates the guest CPU in software (QEMU TCG). On phones that ship Google's **pKVM**, Podroid can run the same VM under the **Android Virtualization Framework** (AVF) for near-native CPU performance: boot times drop from ~15 s to under 3 s, and CPU-bound container workloads run at host speed.

This requires three things, all under your control:

### 1. Check that your phone ships pKVM

```bash
adb shell pm list features | grep virtualization_framework
adb shell getprop ro.boot.hypervisor.vm.supported
adb shell getprop ro.boot.hypervisor.protected_vm.supported
```

If the first line returns `feature:android.software.virtualization_framework` and the two getprop lines return `1`, your device is eligible. If any are empty, it's not, and Podroid will silently fall back to QEMU/TCG; nothing else in this section applies.

Eligibility alone isn't enough: some phones expose pKVM but the AVF guest kernel can't find its console device and panics on boot. Always run **Settings → About → AVF (pKVM) diagnostic** first. If the smoke test fails, leave the backend on Auto (which falls back to QEMU) or force **QEMU (TCG)** explicitly.

### 2. Grant the two AVF permissions via ADB

The `MANAGE_VIRTUAL_MACHINE` and `USE_CUSTOM_VIRTUAL_MACHINE` permissions are `signature|preinstalled|development` — they can't be granted from the in-app UI, but `pm grant` works once per install:

```bash
adb shell pm grant com.excp.podroid android.permission.MANAGE_VIRTUAL_MACHINE
adb shell pm grant com.excp.podroid android.permission.USE_CUSTOM_VIRTUAL_MACHINE
```

The grants persist across reboots and in-place app updates. They survive `adb install -r` but **not** a full uninstall + reinstall.

### 3. Verify and select the backend in-app

Open **Settings → About → AVF (pKVM) diagnostic**. The dialog's bottom line should read:

```
Smoke test:
SUCCESS: AVF accepted our config, VM started + stopped cleanly.
```

Then **Settings → Advanced → Backend** picks how Podroid chooses at startup:

- **Auto** (default) — use AVF if the feature + permissions are both present, otherwise QEMU. The right choice for everyone.
- **AVF (KVM):** force AVF. Useful for debugging; the VM errors explicitly if AVF rejects your config rather than silently falling back.
- **QEMU (TCG):** force software emulation even on a pKVM device. Useful for reproducing reports against the slow path.

Changing the backend requires a VM stop + restart (same as RAM/CPU changes).

If your phone has the feature but you haven't run the `pm grant` commands yet, the home screen shows a one-time hint banner with the commands ready to copy.

## Troubleshooting

### "QEMU crashed (SIGKILL)" / VM dies when I switch to another app

On Android 12+ the system aggressively reaps **phantom processes**: native subprocesses (here, QEMU + the bridge) spawned by an app that isn't visible as a standard Android process. Even with our foreground service keeping the app alive, the phantom children do not inherit that importance and get killed when the app goes to the background or under memory pressure.

Symptoms:
- VM was running, you switched apps for a minute, came back to "VM stopped" or `Error(QEMU crashed (SIGKILL))`.
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
- **Diving into the engine:** [`skill.md`](skill.md) is the deep map: boot pipeline, every native binary, every quirk. AI assistants should read it before touching anything.

## Credits

| | |
|---|---|
| [QEMU](https://www.qemu.org)                   | Machine emulation |
| [Termux](https://github.com/termux/termux-app) | Terminal emulator engine |

Full list (Linux, Alpine, Podman, TigerVNC, PulseAudio, Limbo and more) in [CREDITS.md](CREDITS.md).

## License

[GNU General Public License v2.0](LICENSE). QEMU, Linux, Alpine, Podman and the rest are distributed under their respective upstream licenses.
