# Podroid initramfs builder
# Stage 1: Download Alpine aarch64 ISO + netboot on x86_64
FROM alpine:3.23 AS downloader

RUN apk add --no-cache wget cpio gzip tar xorriso

WORKDIR /downloads

# Download Alpine aarch64 netboot tarball (contains vmlinuz-virt + initramfs-virt)
RUN wget -q https://dl-cdn.alpinelinux.org/alpine/v3.23/releases/aarch64/alpine-netboot-3.23.3-aarch64.tar.gz \
    && mkdir -p /netboot \
    && tar -xf alpine-netboot-3.23.3-aarch64.tar.gz -C /netboot

# Download Alpine aarch64 virt ISO
RUN wget -q https://dl-cdn.alpinelinux.org/alpine/v3.23/releases/aarch64/alpine-virt-3.23.3-aarch64.iso \
    && mkdir -p /iso \
    && xorriso -osirrox on -indev alpine-virt-3.23.3-aarch64.iso -extract / /iso 2>/dev/null || true

# Extract kernel from netboot
RUN cp /netboot/boot/vmlinuz-virt /vmlinuz-virt \
    && ls -lh /vmlinuz-virt

# -----------------------------------------------------------
# Stage 2: Build the custom rootfs on real aarch64 Alpine
# (runs transparently via qemu-user-static + binfmt_misc)
# -----------------------------------------------------------
FROM --platform=linux/arm64/v8 alpine:3.23 AS rootfs-builder

# Install all packages needed in the VM
RUN apk update && apk add --no-cache \
    linux-virt \
    busybox \
    busybox-extras \
    ttyd \
    podman \
    podman-remote \
    netavark \
    aardvark-dns \
    fuse-overlayfs \
    slirp4netns \
    iptables \
    ip6tables \
    shadow-uidmap \
    ca-certificates \
    crun \
    curl \
    e2fsprogs \
    util-linux \
    openrc \
    ncurses-terminfo-base \
    musl-locales

# Podman/container config is written at boot by init-podroid

# Copy the custom init script
COPY init-podroid /init
RUN chmod +x /init

# Clean up apk cache to minimize image size
RUN rm -rf /var/cache/apk/* /tmp/* /var/tmp/* \
    && rm -rf /usr/share/man /usr/share/doc

# -----------------------------------------------------------
# Stage 3: Pack everything into initramfs
# -----------------------------------------------------------
FROM alpine:3.23 AS packer

RUN apk add --no-cache cpio gzip findutils

# Copy kernel from rootfs-builder (matches the modules version)
COPY --from=rootfs-builder /boot/vmlinuz-virt /output/vmlinuz-virt

# Copy the full rootfs from stage 2
COPY --from=rootfs-builder / /rootfs/

# Remove things we don't need in initramfs
RUN rm -rf /rootfs/proc/* /rootfs/sys/* /rootfs/dev/* \
    /rootfs/run/* /rootfs/tmp/* /rootfs/boot

# Create the initramfs
RUN cd /rootfs && \
    find . | cpio -o -H newc 2>/dev/null | gzip -9 > /output/initrd.img && \
    ls -lh /output/initrd.img /output/vmlinuz-virt

# Final stage: just hold the output files
FROM scratch
COPY --from=packer /output/ /
