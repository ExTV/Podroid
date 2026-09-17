#!/bin/bash
# ─────────────────────────────────────────────────────────────────────────────
# Podroid Unified Build & Deploy Script
# Coordinates kernel, initramfs, rootfs, QEMU, and APK builds.
# (libtermux.so is no longer built here — the vendored terminal-emulator
#  module compiles it via AGP's NDK build using src/main/jni/Android.mk.)
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JNILIBS="${SCRIPT_DIR}/app/src/main/jniLibs/arm64-v8a"
ASSETS="${SCRIPT_DIR}/app/src/main/assets"

# ── Colors ────────────────────────────────────────────────────────────────────
BLUE='\033[1;34m'
GREEN='\033[1;32m'
YELLOW='\033[1;33m'
RED='\033[1;31m'
NC='\033[0m' # No Color

log() { printf "${BLUE}==>${NC} %s\n" "$*"; }
warn() { printf "${YELLOW}WARNING:${NC} %s\n" "$*"; }
error() { printf "${RED}ERROR:${NC} %s\n" "$*"; exit 1; }
success() { printf "${GREEN}SUCCESS:${NC} %s\n" "$*"; }

# ── Help ──────────────────────────────────────────────────────────────────────
show_help() {
    cat <<EOF
Podroid Unified Build Tool

Usage: $0 [command] [options]

Commands:
  all           Build everything (Kernel, Initramfs, Rootfs, QEMU, APK)
  kernel        Build custom kernel only (podroid_kernel.config + Linux source)
  initramfs     Build custom kernel + Alpine VM initramfs (vmlinuz + initrd)
  rootfs        Build Alpine rootfs squashfs (alpine-rootfs.squashfs)
  qemu          Build QEMU + podroid-bridge + podroid-launcher
  apk           Build the Android APK (also builds libtermux.so via Gradle NDK)
  deploy        Build APK, uninstall old version, and install to device
  test          Perform full build, install, and automated boot validation
  clean         Remove build artifacts and temporary containers

Options:
  --help        Show this help message

EOF
}

# ── NDK Detection ─────────────────────────────────────────────────────────────
find_ndk() {
    if [ -n "${ANDROID_NDK_ROOT:-}" ] && [ -d "$ANDROID_NDK_ROOT" ]; then
        echo "$ANDROID_NDK_ROOT"
    elif [ -n "${ANDROID_HOME:-}" ] && [ -d "${ANDROID_HOME}/ndk" ]; then
        ls -d "${ANDROID_HOME}/ndk/"* 2>/dev/null | sort -V | tail -1
    elif [ -d "$HOME/Android/Sdk/ndk" ]; then
        ls -d "$HOME/Android/Sdk/ndk/"* 2>/dev/null | sort -V | tail -1
    else
        return 1
    fi
}

# ── Verification Helpers ──────────────────────────────────────────────────────
verify_16kb_align() {
    local lib="$1"
    python3 - "$lib" << 'EOF'
import struct, sys
path = sys.argv[1]
with open(path, 'rb') as f:
    data = f.read()
e_phoff = struct.unpack_from('<Q', data, 32)[0]
e_phentsize = struct.unpack_from('<H', data, 54)[0]
e_phnum = struct.unpack_from('<H', data, 56)[0]
aligns = []
for i in range(e_phnum):
    off = e_phoff + i * e_phentsize
    if struct.unpack_from('<I', data, off)[0] == 1:
        aligns.append(struct.unpack_from('<Q', data, off + 48)[0])
ok = all(a >= 16384 for a in aligns)
if not ok:
    print(f"FAILED: {path} is not 16KB page aligned!")
    sys.exit(1)
EOF
}

# ── Build Functions ───────────────────────────────────────────────────────────

build_kernel() {
    local kernel_ver
    kernel_ver=$(grep -E '^podroidKernelVersion=' "${SCRIPT_DIR}/gradle.properties" | cut -d= -f2)
    log "Building custom kernel ${kernel_ver} for aarch64 (Docker)..."
    docker build --network=host \
        --build-arg "KERNEL_VERSION=${kernel_ver}" \
        -t podroid-kernel-builder --target kernel-builder "$SCRIPT_DIR"
    log "Extracting kernel artifact..."
    docker rm -f podroid-kernel-extract 2>/dev/null || true
    docker create --name podroid-kernel-extract podroid-kernel-builder
    mkdir -p "$ASSETS"
    docker cp podroid-kernel-extract:/output/vmlinuz-virt "$ASSETS/vmlinuz-virt"
    docker rm podroid-kernel-extract >/dev/null
    success "Custom kernel ready."
}

build_initramfs() {
    local kernel_ver
    kernel_ver=$(grep -E '^podroidKernelVersion=' "${SCRIPT_DIR}/gradle.properties" | cut -d= -f2)
    log "Building custom kernel + Alpine Initramfs (Docker)..."
    docker build --network=host \
        --build-arg "KERNEL_VERSION=${kernel_ver}" \
        -t podroid-builder --target packer "$SCRIPT_DIR"

    log "Extracting initramfs artifacts..."
    docker rm podroid-extract 2>/dev/null || true
    docker create --name podroid-extract podroid-builder /bin/true
    mkdir -p "$ASSETS"
    docker cp podroid-extract:/output/vmlinuz-virt "$ASSETS/vmlinuz-virt"
    docker cp podroid-extract:/output/initrd.img "$ASSETS/initrd.img"
    docker rm podroid-extract >/dev/null
    success "Kernel + initramfs ready."
}

build_rootfs() {
    log "Building Alpine rootfs squashfs..."
    local sysver
    sysver=$(grep -E '^[[:space:]]*versionCode[[:space:]]*=' "${SCRIPT_DIR}/app/build.gradle.kts" | grep -oE '[0-9]+' | head -1)
    docker build -f "${SCRIPT_DIR}/build-rootfs/Dockerfile.rootfs" \
        -t podroid-rootfs:latest \
        --build-arg "SYSTEM_VERSION=${sysver:-0}" \
        --output type=local,dest="${ASSETS}" \
        "${SCRIPT_DIR}/build-rootfs/"
    success "Built ${ASSETS}/alpine-rootfs.squashfs ($(du -h "${ASSETS}/alpine-rootfs.squashfs" | cut -f1)), system-version ${sysver:-0}"
}

build_qemu() {
    local qemu_ver
    qemu_ver=$(grep -E '^podroidQemuVersion=' "${SCRIPT_DIR}/gradle.properties" | cut -d= -f2)
    log "Building QEMU ${qemu_ver} for Android ARM64 (Docker)..."
    
    docker build --build-arg "QEMU_VERSION=${qemu_ver}" \
        -t podroid-qemu-builder --target final "${SCRIPT_DIR}"
        
    log "Extracting QEMU artifacts..."
    docker rm -f podroid-qemu-extract 2>/dev/null || true
    docker create --name podroid-qemu-extract podroid-qemu-builder /bin/true
    
    mkdir -p "$JNILIBS" "$ASSETS/qemu/keymaps"
    docker cp podroid-qemu-extract:/libqemu-system-aarch64.so "$JNILIBS/"
    docker cp podroid-qemu-extract:/libslirp.so               "$JNILIBS/"
    docker cp podroid-qemu-extract:/libpodroid-bridge.so      "$JNILIBS/"
    docker cp podroid-qemu-extract:/libpodroid-launcher.so    "$JNILIBS/"
    docker cp podroid-qemu-extract:/qemu/efi-virtio.rom        "$ASSETS/qemu/"
    docker cp podroid-qemu-extract:/qemu/keymaps/.             "$ASSETS/qemu/keymaps/"
    docker rm podroid-qemu-extract >/dev/null
    
    verify_16kb_align "$JNILIBS/libqemu-system-aarch64.so"
    success "QEMU and bridge ready."
}

build_apk() {
    log "Building APK via Gradle..."
    ./gradlew assembleDebug
    success "APK built: app/build/outputs/apk/debug/app-debug.apk"
}

deploy_apk() {
    log "Deploying to device..."
    adb install -r app/build/outputs/apk/debug/app-debug.apk
    success "Deployed and ready."
}

run_boot_test() {
    local pkg="com.excp.podroid.debug"
    local activity="com.excp.podroid.MainActivity"
    local timeout=60
    
    log "Starting Automated Boot Test..."
    
    # Check for device
    adb devices 2>/dev/null | grep -q 'device$' || error "No device connected via ADB."
    
    # Build and Install
    build_apk
    deploy_apk
    
    # Reset State
    log "Resetting boot log for clean test..."
    adb shell am force-stop "$pkg" 2>/dev/null || true
    adb shell run-as "$pkg" rm -f files/console.log 2>/dev/null || true
    
    # Launch
    log "Launching App..."
    adb shell am start -n "$pkg/$activity" >/dev/null 2>&1
    
    echo -e "${YELLOW}>>> PLEASE PRESS 'Start Podman' IN THE APP NOW <<<${NC}"
    
    # Poll console log
    log "Waiting for VM to boot (timeout: ${timeout}s)..."
    local boot_ok=false
    for i in $(seq 1 "$timeout"); do
        local console
        console=$(adb shell run-as "$pkg" cat files/console.log 2>/dev/null || echo "")
        if echo "$console" | grep -q "Ready!"; then
            boot_ok=true
            break
        fi
        printf "."
        sleep 1
    done
    echo ""
    
    if [ "$boot_ok" = false ]; then
        error "VM failed to boot within ${timeout}s. Check 'adb logcat'."
    fi
    
    # Validation
    log "Validating boot output..."
    local console
    console=$(adb shell run-as "$pkg" cat files/console.log 2>/dev/null || echo "")
    
    local errors=0
    local checks=("Loading kernel modules" "Network found" "Almost ready" "Ready!")
    for check in "${checks[@]}"; do
        if echo "$console" | grep -q "$check"; then
            success "Check passed: $check"
        else
            warn "Check FAILED: $check"
            errors=$((errors + 1))
        fi
    done
    
    if [ "$errors" -eq 0 ]; then
        success "Automated Boot Test PASSED."
    else
        error "Automated Boot Test FAILED with $errors errors."
    fi
}

# ── Main Logic ────────────────────────────────────────────────────────────────

[ $# -eq 0 ] && { show_help; exit 1; }

case "$1" in
    kernel)    build_kernel ;;
    initramfs) build_initramfs ;;
    rootfs)    build_rootfs ;;
    qemu)      build_qemu ;;
    apk)       build_apk ;;
    deploy)    build_apk && deploy_apk ;;
    test)      run_boot_test ;;
    all)
        build_initramfs
        build_rootfs
        build_qemu
        build_apk
        ;;
    clean)
        log "Cleaning up..."
        ./gradlew clean
        docker rmi podroid-builder podroid-qemu-builder podroid-rootfs:latest 2>/dev/null || true
        success "Cleaned."
        ;;
    *)
        show_help
        exit 1
        ;;
esac
