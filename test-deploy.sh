#!/bin/bash
# Podroid automated build → install → reset → boot test
# Usage: ./test-deploy.sh [--rebuild-initramfs]
set -e

PKG="com.excp.podroid.debug"
ACTIVITY="com.excp.podroid.MainActivity"
LOG_CMD="run-as $PKG cat files/console.log"
BOOT_TIMEOUT=60
SETTLE_TIME=5

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
CYAN='\033[0;36m'
NC='\033[0m'

log()  { echo -e "${CYAN}[$(date +%H:%M:%S)]${NC} $1"; }
ok()   { echo -e "${GREEN}[OK]${NC} $1"; }
warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
fail() { echo -e "${RED}[FAIL]${NC} $1"; }

# ── Step 0: Check ADB ──
if ! adb devices 2>/dev/null | grep -q 'device$'; then
    fail "No device connected via ADB"
    exit 1
fi
ok "Device connected"

# ── Step 1: Rebuild initramfs (optional) ──
if [ "$1" = "--rebuild-initramfs" ] || [ "$1" = "-r" ]; then
    log "Rebuilding initramfs..."
    ./docker-build-initramfs.sh 2>&1 | tail -3
    ok "Initramfs rebuilt"
fi

# ── Step 2: Build APK ──
log "Building debug APK..."
./gradlew assembleDebug 2>&1 | tail -3
ok "APK built"

# ── Step 3: Install ──
log "Installing APK..."
adb install -r app/build/outputs/apk/debug/app-debug.apk 2>&1
ok "APK installed"

# ── Step 4: Force stop app (clean state) ──
log "Stopping app..."
adb shell am force-stop "$PKG" 2>/dev/null || true
sleep 1

# ── Step 5: Reset VM (delete storage.img) ──
log "Resetting VM storage..."
adb shell run-as "$PKG" rm -f files/storage.img 2>/dev/null || true
adb shell run-as "$PKG" rm -f files/console.log 2>/dev/null || true
ok "VM reset"

# ── Step 6: Launch app ──
log "Launching app..."
adb shell am start -n "$PKG/$ACTIVITY" >/dev/null 2>&1
sleep 2
ok "App launched"

# ── Step 7: Wait for user to start VM ──
echo ""
echo -e "${YELLOW}>>> Press 'Start Podman' in the app, then press Enter here <<<${NC}"
read -r
echo ""

# ── Step 8: Wait for boot ──
log "Waiting for VM to boot (timeout: ${BOOT_TIMEOUT}s)..."
BOOT_OK=false
for i in $(seq 1 "$BOOT_TIMEOUT"); do
    CONSOLE=$(adb shell run-as "$PKG" cat files/console.log 2>/dev/null || echo "")
    if echo "$CONSOLE" | grep -q "Ready!"; then
        BOOT_OK=true
        break
    fi
    if echo "$CONSOLE" | grep -q "Internet:"; then
        BOOT_OK=true
        break
    fi
    printf "."
    sleep 1
done
echo ""

if [ "$BOOT_OK" = true ]; then
    ok "VM booted successfully"
else
    fail "VM failed to boot within ${BOOT_TIMEOUT}s"
    echo ""
    echo "=== Console log ==="
    adb shell run-as "$PKG" cat files/console.log 2>/dev/null | tail -20
    exit 1
fi

# ── Step 9: Let VM settle ──
log "Letting VM settle for ${SETTLE_TIME}s..."
sleep "$SETTLE_TIME"

# ── Step 10: Show console log ──
echo ""
echo "════════════════════════════════════════"
echo "  Console Output"
echo "════════════════════════════════════════"
adb shell run-as "$PKG" cat files/console.log 2>/dev/null
echo ""
echo "════════════════════════════════════════"

# ── Step 11: Verify boot output ──
CONSOLE=$(adb shell run-as "$PKG" cat files/console.log 2>/dev/null || echo "")
ERRORS=0

check() {
    if echo "$CONSOLE" | grep -q "$1"; then
        ok "$2"
    else
        fail "$2"
        ERRORS=$((ERRORS + 1))
    fi
}

echo ""
echo "=== Boot Checks ==="
check "Podroid - Alpine Linux" "Banner displayed"
check "IP:" "IP address shown"
check "Persistent: yes" "Persistent storage active"
check "Internet:" "Internet check ran"
check "Ready!" "Boot completed"
check "Loading kernel modules" "Kernel modules loaded"
check "Found:" "Network interface found"

# Check for errors
if echo "$CONSOLE" | grep -qi "panic\|oops\|segfault"; then
    fail "Kernel panic or crash detected!"
    ERRORS=$((ERRORS + 1))
fi

echo ""
if [ "$ERRORS" -eq 0 ]; then
    ok "All checks passed!"
    echo ""
    echo "VM is running. Open the terminal in the app to test:"
    echo "  - Press SYNC to set terminal size"
    echo "  - apk add neovim btop"
    echo "  - nvim"
    echo "  - btop"
else
    fail "$ERRORS check(s) failed"
fi

echo ""
echo "To view live log:  adb shell run-as $PKG cat files/console.log"
echo "To take screenshot: adb exec-out screencap -p > screenshot.png"
