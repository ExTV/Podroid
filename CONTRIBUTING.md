# Contributing to Podroid

Thanks for considering a contribution. Bug reports, feature requests, and pull requests are all welcome.

Before you start, please skim [`CLAUDE.md`](CLAUDE.md). It documents the VM-engine abstraction and both backends, the boot pipeline, every native binary, and the design quirks you need to know to make changes that don't regress.

## Getting started

```sh
git clone https://github.com/ExTV/Podroid.git
cd Podroid
```

You will need:

- **Docker 20.10+** for the kernel, initramfs, rootfs, and QEMU build pipelines
- **Android NDK r27c** for the bridge and Termux native libraries
- **Android SDK** with platform 36 + build-tools
- An **arm64 Android device** running **Android 9.0+ (API 28)** for testing

## Build pipeline

`build-all.sh` orchestrates every component:

```sh
./build-all.sh kernel       # custom Linux 6.6.87 (~5–10 min, Docker-cached)
./build-all.sh initramfs    # kernel + minimal initramfs
./build-all.sh rootfs       # Alpine 3.23 squashfs (~30 s, Docker-cached)
./build-all.sh qemu         # QEMU 11 + podroid-bridge (~30 min first run)
./build-all.sh termux       # libtermux.so via local NDK
./gradlew installDebug      # build + install the APK
```

Or, for the common case where you only changed Kotlin / UI code:

```sh
./gradlew installDebug
```

To validate a full rebuild end-to-end:

```sh
./build-all.sh test         # deploys APK, polls console.log for "Ready!"
```

## Reporting bugs

Please open an issue using the **Bug Report** template. The most useful single thing you can attach is the diagnostic log:

`Settings → Diagnostics → Export Log`

It bundles app version, device model + Android version, settings, and full logcat in one file. If the bug is VM-side, also include the VM console:

```sh
adb shell run-as com.excp.podroid.debug cat files/console.log
```

## Submitting changes

1. Fork the repository and create a topic branch (`fix/issue-42`, `feature/whatever`).
2. Keep pull requests focused: one fix or one feature per PR.
3. Test on a real arm64 device before submitting. Emulators do not exercise the QEMU + native binary path the way real hardware does.
4. If your change is user-facing, update [`README.md`](README.md). If it changes the architecture, boot pipeline, terminal layer, or kernel options, update [`CLAUDE.md`](CLAUDE.md) too.
5. Match the existing code style of the file you are editing.

## Project layout

```
Podroid/
├── app/                                  Android application (Jetpack Compose, Hilt)
│   └── src/main/
│       ├── java/com/excp/podroid/
│       │   ├── engine/                   PodroidQemu, QmpClient, VmState
│       │   ├── service/                  Foreground service + boot-stage notification
│       │   ├── data/repository/          DataStore-backed settings & port forwards
│       │   └── ui/                       Compose screens + theme
│       ├── jniLibs/arm64-v8a/            QEMU, podroid-bridge, libslirp, libtermux
│       └── assets/                       kernel, initramfs, squashfs, fonts, themes
├── init-podroid                          Minimal initramfs script (~45 lines)
├── podroid-bridge.c                      Native PTY ↔ virtio-console relay
├── Dockerfile                            Kernel + initramfs + QEMU build pipeline
├── build-rootfs/                         Alpine squashfs build pipeline
│   ├── Dockerfile.rootfs
│   ├── build-rootfs.sh
│   └── files/                            OpenRC services baked into the squashfs
├── build-all.sh                          Unified build / deploy script
├── podroid_kernel.config                 Custom kernel Kconfig fragment
└── docs/                                 GitHub Pages site
```

## Code style

- Kotlin: follow the [official conventions](https://kotlinlang.org/docs/coding-conventions.html).
- Keep it simple. No premature abstractions.
- Match the surrounding file's style. Consistency beats personal preference.
- Comments explain *why*, not *what*. Self-documenting names go further than prose.

## License

By contributing, you agree that your work will be licensed under the **GNU General Public License v2.0**, the same license as the project.
