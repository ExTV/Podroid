#!/bin/bash
# ─────────────────────────────────────────────────────────────────────────────
# build-qemu-android.sh
# Builds the configured QEMU version for Android ARM64 with virtfs support.
# Replaces app/src/main/jniLibs/arm64-v8a/libqemu-system-aarch64.so
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
IMAGE="podroid-qemu-builder"
CONTAINER="podroid-qemu-extract"
QEMU_VERSION="$(grep -E '^podroidQemuVersion=' "${SCRIPT_DIR}/gradle.properties" | cut -d= -f2)"

if [ -z "${QEMU_VERSION}" ]; then
    echo "ERROR: podroidQemuVersion is not set in gradle.properties" >&2
    exit 1
fi

echo "=== [1/4] Building QEMU ${QEMU_VERSION} for Android ARM64 ==="
echo "      This will take 20-40 minutes on first run."
echo ""

docker build \
    -f "${SCRIPT_DIR}/Dockerfile.qemu" \
    --build-arg "QEMU_VERSION=${QEMU_VERSION}" \
    -t "${IMAGE}" \
    "${SCRIPT_DIR}"

echo ""
echo "=== [2/4] Extracting artifacts ==="

# Clean up any previous container
docker rm -f "${CONTAINER}" 2>/dev/null || true

# Create a temporary container from the artifacts stage
docker create --name "${CONTAINER}" "${IMAGE}"

JNILIBS="${SCRIPT_DIR}/app/src/main/jniLibs/arm64-v8a"
ASSETS="${SCRIPT_DIR}/app/src/main/assets"

# Extract binaries
docker cp "${CONTAINER}:/out/libqemu-system-aarch64.so" "${JNILIBS}/libqemu-system-aarch64.so"
docker cp "${CONTAINER}:/out/libslirp.so"               "${JNILIBS}/libslirp.so"

# Extract ROM and keymaps
docker cp "${CONTAINER}:/out/qemu/efi-virtio.rom"        "${ASSETS}/qemu/efi-virtio.rom"
docker cp "${CONTAINER}:/out/qemu/keymaps/."             "${ASSETS}/qemu/keymaps/"

docker rm "${CONTAINER}"

echo ""
echo "=== [3/4] Verifying binary ==="
echo -n "  Architecture: "
file "${JNILIBS}/libqemu-system-aarch64.so" | grep -oP 'ARM aarch64.*?(?=,)'

echo -n "  Size:         "
du -sh "${JNILIBS}/libqemu-system-aarch64.so" | cut -f1

echo -n "  Linked libs:  "
readelf -d "${JNILIBS}/libqemu-system-aarch64.so" 2>/dev/null \
    | grep NEEDED | grep -oP '\[\K[^\]]+' | tr '\n' ' '
echo ""

echo -n "  QEMU version: "
strings "${JNILIBS}/libqemu-system-aarch64.so" \
    | grep -m1 -oP 'QEMU emulator version \K.*' || echo "unknown"

echo -n "  virtio-9p:    "
strings "${JNILIBS}/libqemu-system-aarch64.so" | grep -c "virtio-9p" || echo "0"

echo -n "  16KB pages:   "
python3 - "${JNILIBS}/libqemu-system-aarch64.so" << 'EOF'
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
print("PASS" if ok else "FAIL")
if not ok:
    sys.exit(1)
EOF

echo ""
echo "=== [4/4] Done! ==="
echo ""
echo "  libqemu-system-aarch64.so → ${JNILIBS}/"
echo "  libslirp.so               → ${JNILIBS}/"
echo "  ROM + keymaps             → ${ASSETS}/qemu/"
echo ""
echo "  Now run: ./gradlew assembleDebug"
echo ""
