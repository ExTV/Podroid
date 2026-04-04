#!/bin/bash
# ─────────────────────────────────────────────────────────────────────────────
# build-qemu-android.sh
# Builds QEMU 11.0.0-rc2 for Android ARM64 with virtfs support.
# Replaces app/src/main/jniLibs/arm64-v8a/libqemu-system-aarch64.so
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
IMAGE="podroid-qemu-builder"
CONTAINER="podroid-qemu-extract"

echo "=== [1/4] Building QEMU 11.0.0-rc2 for Android ARM64 ==="
echo "      This will take 20-40 minutes on first run."
echo ""

docker build \
    -f "${SCRIPT_DIR}/Dockerfile.qemu" \
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

echo -n "  virtio-9p:    "
strings "${JNILIBS}/libqemu-system-aarch64.so" | grep -c "virtio-9p" || echo "0"

echo ""
echo "=== [4/4] Done! ==="
echo ""
echo "  libqemu-system-aarch64.so → ${JNILIBS}/"
echo "  libslirp.so               → ${JNILIBS}/"
echo "  ROM + keymaps             → ${ASSETS}/qemu/"
echo ""
echo "  Now run: ./gradlew assembleDebug"
echo ""
