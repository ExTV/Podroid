#!/bin/bash
# Podroid initramfs builder using Docker
# Builds a custom Alpine aarch64 initramfs with ttyd + podman
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ASSETS_DIR="$SCRIPT_DIR/app/src/main/assets"
IMAGE_NAME="podroid-builder"
CONTAINER_NAME="podroid-extract"

echo "=== Podroid Docker Initramfs Builder ==="
echo ""

# Step 1: Build the Docker image
echo "[1/3] Building Docker image (this may take a few minutes)..."
echo "       - Stage 1: Downloading Alpine aarch64 ISO + netboot"
echo "       - Stage 2: Installing ttyd + podman in aarch64 rootfs (via qemu-user)"
echo "       - Stage 3: Packing initramfs"
echo ""

docker build -t "$IMAGE_NAME" "$SCRIPT_DIR"

echo ""
echo "[2/3] Extracting artifacts..."

# Step 2: Extract files from the image
# Create a temporary container from the scratch-based final image
# (scratch images need a dummy command for docker create)
docker rm "$CONTAINER_NAME" 2>/dev/null || true
docker create --name "$CONTAINER_NAME" "$IMAGE_NAME" /bin/true

mkdir -p "$ASSETS_DIR"

# Copy out the kernel and initramfs
docker cp "$CONTAINER_NAME:/vmlinuz-virt" "$ASSETS_DIR/vmlinuz-virt"
docker cp "$CONTAINER_NAME:/initrd.img" "$ASSETS_DIR/initrd.img"

# Cleanup container
docker rm "$CONTAINER_NAME" >/dev/null

echo ""
echo "[3/3] Done!"
echo ""
echo "Output files:"
ls -lh "$ASSETS_DIR/vmlinuz-virt" "$ASSETS_DIR/initrd.img"
echo ""
echo "Assets directory: $ASSETS_DIR"
echo ""
echo "=== Build complete! ==="
