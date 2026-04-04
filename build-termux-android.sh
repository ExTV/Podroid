#!/bin/bash
# ─────────────────────────────────────────────────────────────────────────────
# build-termux-android.sh
# Rebuilds libtermux.so from Termux v0.118.1 source with 16KB page alignment.
# Replaces app/src/main/jniLibs/arm64-v8a/libtermux.so
#
# Required: Android NDK (auto-detected via $ANDROID_HOME or ~/Android/Sdk)
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TERMUX_TAG="v0.118.1"
TERMUX_REPO="https://github.com/termux/termux-app.git"
BUILD_DIR="/tmp/termux-jni-build"
OUT_DIR="${SCRIPT_DIR}/app/src/main/jniLibs/arm64-v8a"

# ── Locate NDK ────────────────────────────────────────────────────────────────
if [ -n "${ANDROID_NDK_ROOT:-}" ] && [ -d "$ANDROID_NDK_ROOT" ]; then
    NDK="$ANDROID_NDK_ROOT"
elif [ -n "${ANDROID_HOME:-}" ] && [ -d "${ANDROID_HOME}/ndk" ]; then
    NDK=$(ls -d "${ANDROID_HOME}/ndk/"* 2>/dev/null | sort -V | tail -1)
elif [ -d "$HOME/Android/Sdk/ndk" ]; then
    NDK=$(ls -d "$HOME/Android/Sdk/ndk/"* 2>/dev/null | sort -V | tail -1)
else
    echo "ERROR: Could not find Android NDK. Set ANDROID_NDK_ROOT or ANDROID_HOME." >&2
    exit 1
fi
echo "=== Using NDK: $NDK"

LLVM="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
CC="$LLVM/bin/aarch64-linux-android26-clang"
if [ ! -f "$CC" ]; then
    echo "ERROR: NDK clang not found at $CC" >&2
    exit 1
fi

# ── Clone Termux source ───────────────────────────────────────────────────────
echo ""
echo "=== [1/3] Fetching Termux $TERMUX_TAG source ==="

rm -rf "$BUILD_DIR"
git clone --depth=1 --branch "$TERMUX_TAG" "$TERMUX_REPO" "$BUILD_DIR"

JNI_DIR="$BUILD_DIR/terminal-emulator/src/main/jni"
if [ ! -f "$JNI_DIR/termux.c" ]; then
    echo "ERROR: Expected JNI source at $JNI_DIR/termux.c — repo structure may have changed." >&2
    exit 1
fi

# ── Compile directly with NDK clang ──────────────────────────────────────────
echo ""
echo "=== [2/3] Building libtermux.so with 16KB page alignment ==="

SYSROOT="$LLVM/sysroot"

"$CC" \
    --sysroot="$SYSROOT" \
    -O2 \
    -fPIC \
    -fvisibility=hidden \
    -shared \
    -o "$BUILD_DIR/libtermux.so" \
    "$JNI_DIR/termux.c" \
    -Wl,-soname,libtermux.so \
    -Wl,-z,max-page-size=16384 \
    -llog \
    -landroid

# ── Verify and install ────────────────────────────────────────────────────────
echo ""
echo "=== [3/3] Verifying and installing ==="

python3 - "$BUILD_DIR/libtermux.so" << 'EOF'
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
print(f"  Alignment check: {'PASS' if ok else 'FAIL'}  LOAD aligns={[hex(a) for a in aligns]}")
if not ok:
    sys.exit(1)
EOF

mkdir -p "$OUT_DIR"
cp "$BUILD_DIR/libtermux.so" "$OUT_DIR/libtermux.so"

SIZE=$(du -sh "$OUT_DIR/libtermux.so" | cut -f1)
echo "  Size:         $SIZE"
echo "  libtermux.so → $OUT_DIR/"
echo ""
echo "=== Done! Now run: ./gradlew assembleDebug"
echo ""
