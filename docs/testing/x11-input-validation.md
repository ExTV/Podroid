# X11 cursor and touchpad validation

Test date: 2026-11-18. Device: Xiaomi tablet, model M367FC, Android 17, arm64-v8a, with a keyboard touchpad.

## Why this change is needed

The Android pointer and the guest cursor were both visible in the viewer. Rendering the server-provided cursor locally removes the duplicate pointer and lets its position update without waiting for another framebuffer update.

The keyboard touchpad reports mouse-source events with a finger tool type. Checking only the tool type discarded hover movement, so the guest cursor moved only when clicked. Its two-finger scroll is a classified, single-pointer swipe rather than an ordinary wheel event. Treating it as pointer movement moved the cursor instead of scrolling. The input handler now distinguishes these gestures and keeps the cursor anchored while scrolling. A saved Natural/Traditional preference lets users choose their expected direction.

## Results and coverage

| Check | Result |
| --- | --- |
| Full `:app:testDebugUnitTest` suite | 488 tests, no failures, errors, or skips |
| Cursor decoding and scroll direction tests | Included in the 488 tests |
| Real touchpad movement and clicking | Confirmed by the user on the tablet |
| Real two-finger scrolling | Confirmed by the user, including direction and speed |
| Natural/Traditional direction switching | Device event replay verified opposite scrolling results and an anchored cursor |
| Gesture remainder reset | Separate short swipes did not accumulate into an unintended scroll |
| QEMU boot test | Passed all four checks in `build-all.sh test` within its unchanged 60-second timeout |
| QEMU viewer connection | Entered the in-app X11 viewer connected to the isolated QEMU VM's Xvnc; the fresh guest had no desktop environment installed |
| Original installation | Original package version, update timestamp, application PID and VM PID remained unchanged |

The detailed input replay used an isolated TigerVNC 1.15.0 desktop in a host Docker container. This exercises Android input dispatch, Compose, the viewer, and RFB, but does not itself exercise a Podroid VM backend. The additional boot run below exercises the actual QEMU backend.

## Isolated QEMU boot run

Running the default deployment alongside an existing installation would reuse the debug package and collide with the original VM's host ports. To protect the existing environment, `./build-all.sh test` was run from a disposable checkout with these test-only substitutions:

- Package `com.excp.podroid.boottest`, label `Podroid 启动测试`, and its own private app directory and newly created VM disk.
- QEMU selected explicitly; 512 MiB RAM, two vCPUs, and a 1 GiB data disk. SSH, USB passthrough, shared storage, and boot autostart remained disabled. The setup wizard was skipped only in the test checkout.
- Loopback host port 15910 forwarded to guest VNC port 5900; loopback host port 14723 forwarded to guest audio port 4713. The unused SSH host port was changed to 19932 as an additional precaution.
- The boot script targeted the new package and launched its existing foreground `START_VM` intent instead of waiting for a manual Start button press. The timeout and all boot-log assertions were unchanged.
- The existing Gradle 9.3.1 distribution and installed NDK 27.0.12077973 were selected for the local build. These tooling and isolation substitutions are not product changes.

No native or guest-image source changed in this contribution. The kernel, initrd, squashfs and QEMU runtime libraries were extracted read-only from the installed Podroid 1.3.0 APK, not from its private VM data. That APK's SHA-256 was:

```text
705d7f400e32d8e0b0f49b52c97dc25cd20bc8719bcecc1d24eb070b4456d6a3
```

The first attempt was blocked by Android's new-package installation confirmation. After the user allowed installation, the same test completed successfully:

```text
Check passed: Loading kernel modules
Check passed: Network found
Check passed: Almost ready
Check passed: Ready!
Automated Boot Test PASSED.
```

The test package's control provider independently reported:

```text
ok vm=running backend=qemu selection=QEMU stage=Ready forwards=none vc=34 uml=not_probed
```

After validation, only the test package received STOP_VM; its provider reported `vm=stopped`. No original-package stop, update, clear-data, disk-copy or disk-write operation was performed.

## Limits to disclose in a PR

- AVF and UML were not tested with the changed viewer. Do not describe this as all-backend validation.
- The boot run reused packaged native artifacts; it was not a from-source rebuild of the kernel, rootfs or QEMU.
- The fresh QEMU guest displayed a bare Xvnc surface. The detailed GUI input checks used the separate TigerVNC test desktop.
- The guest boot log contained an LXC iptables `RULE_APPEND failed` warning despite all boot checks passing. Container networking was not validated by this X11-focused change.
- Right-click, dragging, fullscreen transitions and reconnect behavior were not separately verified in the final manual round.
- The direction preference was verified on disk after a process restart. A later attempt to verify the restored menu selection stopped at the foreground-package guard before clicking, so post-restart menu rendering remains unverified.
