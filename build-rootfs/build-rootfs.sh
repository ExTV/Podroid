#!/bin/sh
set -eu
ROOTFS=/work/rootfs

# ALPINE_VERSION comes from the Dockerfile ENV (full release like 3.23.4).
# Strip the patch component to get the major branch (e.g. 3.23) used in repo URLs.
: "${ALPINE_VERSION:?ALPINE_VERSION must be set (e.g. 3.23.4)}"
ALPINE_BRANCH="${ALPINE_VERSION%.*}"

mkdir -p "$ROOTFS/etc/apk"
cat > "$ROOTFS/etc/apk/repositories" <<EOF
https://dl-cdn.alpinelinux.org/alpine/v${ALPINE_BRANCH}/main
https://dl-cdn.alpinelinux.org/alpine/v${ALPINE_BRANCH}/community
EOF

apk -X "https://dl-cdn.alpinelinux.org/alpine/v${ALPINE_BRANCH}/main" \
    -X "https://dl-cdn.alpinelinux.org/alpine/v${ALPINE_BRANCH}/community" \
    -U --allow-untrusted --root "$ROOTFS" --initdb add \
    alpine-base \
    openrc \
    busybox-openrc \
    bash \
    podman \
    docker docker-openrc docker-cli-compose \
    lxc lxc-templates lxc-openrc lxc-bridge \
    crun \
    fuse-overlayfs \
    iptables \
    ip6tables \
    nftables \
    bridge-utils \
    iproute2 \
    dropbear dropbear-openrc \
    curl \
    ca-certificates \
    shadow shadow-uidmap \
    slirp4netns \
    aardvark-dns netavark \
    libcap-utils \
    doas sudo \
    gcompat \
    gzip \
    tigervnc \
    pulseaudio \
    pulseaudio-utils \
    font-misc-misc \
    font-cursor-misc \
    ttf-dejavu

# Apply file capabilities to newuidmap/newgidmap. apk's package install often
# does this, but we set them explicitly so the squashfs ships with the
# correct security.capability xattr (preserved by mksquashfs without -no-xattrs).
if command -v setcap >/dev/null 2>&1; then
    setcap cap_setuid+ep "$ROOTFS/usr/bin/newuidmap" 2>/dev/null || true
    setcap cap_setgid+ep "$ROOTFS/usr/bin/newgidmap" 2>/dev/null || true
fi

# Ensure doas and sudo are setuid-root. apk usually does this, but on
# overlay-mounted build hosts it can silently fail.
chmod u+s "$ROOTFS/usr/bin/doas"  2>/dev/null || true
chmod u+s "$ROOTFS/usr/bin/sudo"  2>/dev/null || true

# doas: members of the `wheel` group can become root after entering their
# password (cached for ~5 min). Standard *BSD/Alpine convention.
mkdir -p "$ROOTFS/etc/doas.d"
echo "permit persist :wheel" > "$ROOTFS/etc/doas.d/doas.conf"
chmod 0400 "$ROOTFS/etc/doas.d/doas.conf"

# sudo: equivalent rule for users who prefer sudo over doas.
mkdir -p "$ROOTFS/etc/sudoers.d"
echo "%wheel ALL=(ALL) ALL" > "$ROOTFS/etc/sudoers.d/wheel"
chmod 0440 "$ROOTFS/etc/sudoers.d/wheel"

# Set root password to "podroid" (pre-hashed with openssl).
# We can't run chpasswd inside the aarch64 rootfs from an x86_64 host,
# so write the SHA-512 hash directly into /etc/shadow.
ROOT_HASH=$(openssl passwd -6 -salt podroid podroid)
sed -i "s|^root:[^:]*:|root:${ROOT_HASH}:|" "$ROOTFS/etc/shadow"

# Strip docs/man/locale to shrink squashfs
rm -rf "$ROOTFS/usr/share/man" "$ROOTFS/usr/share/doc" \
       "$ROOTFS/usr/share/locale" "$ROOTFS/usr/share/info"

# Pre-create podman storage dirs (saves first-boot mkdir)
mkdir -p "$ROOTFS/var/lib/containers/storage" \
         "$ROOTFS/run/containers/storage" \
         "$ROOTFS/run/libpod" \
         "$ROOTFS/run/crun"

# Copy custom service files into the rootfs
cp /work/files/etc/init.d/podroid-bootstrap "$ROOTFS/etc/init.d/"
cp /work/files/etc/init.d/podroid-network   "$ROOTFS/etc/init.d/"
cp /work/files/etc/init.d/podroid-resize    "$ROOTFS/etc/init.d/"
cp /work/files/etc/init.d/podroid-ready     "$ROOTFS/etc/init.d/"
cp /work/files/etc/init.d/podroid-x11       "$ROOTFS/etc/init.d/"
cp /work/files/etc/init.d/podroid-vsock     "$ROOTFS/etc/init.d/"
chmod +x "$ROOTFS/etc/init.d/podroid-"*

# Copy /usr/local/bin scripts (resize daemon + login wrapper + getty selector)
mkdir -p "$ROOTFS/usr/local/bin"
cp /work/files/usr/local/bin/podroid-resize "$ROOTFS/usr/local/bin/"
cp /work/files/usr/local/bin/podroid-login  "$ROOTFS/usr/local/bin/"
cp /work/files/usr/local/bin/podroid-getty  "$ROOTFS/usr/local/bin/"
# podroid-vsock-agent is COPY'd in from the vsock-builder Docker stage. Make
# sure it's executable (cross-arch COPY can lose the mode bit on some buildkit
# versions).
chmod +x "$ROOTFS/usr/local/bin/podroid-vsock-agent" 2>/dev/null || true
chmod +x "$ROOTFS/usr/local/bin/podroid-"*
mkdir -p "$ROOTFS/etc/conf.d"
cp /work/files/etc/conf.d/podroid "$ROOTFS/etc/conf.d/"
# vsock agent's initial forward table (read at podroid-vsock startup).
mkdir -p "$ROOTFS/etc/podroid"
cp /work/files/etc/podroid/forwards.conf "$ROOTFS/etc/podroid/forwards.conf"
chmod 0644 "$ROOTFS/etc/podroid/forwards.conf"
cp /work/files/etc/inittab "$ROOTFS/etc/inittab"
cp /work/files/etc/rc.conf "$ROOTFS/etc/rc.conf"

# /etc/profile.d/*.sh — sourced by Alpine's /etc/profile in login shells.
# podroid-color.sh: COLORTERM=truecolor (24-bit color). podroid-x11.sh:
# DISPLAY / PULSE_SERVER for the in-app GUI viewer. Copy by explicit name so
# a renamed/removed asset fails the build (set -e) instead of silently
# shipping a squashfs without these exports.
mkdir -p "$ROOTFS/etc/profile.d"
cp /work/files/etc/profile.d/podroid-color.sh "$ROOTFS/etc/profile.d/"
cp /work/files/etc/profile.d/podroid-x11.sh   "$ROOTFS/etc/profile.d/"
chmod 0644 "$ROOTFS/etc/profile.d/podroid-color.sh" "$ROOTFS/etc/profile.d/podroid-x11.sh"

# /etc/containers/storage.conf — pin Podman to the in-kernel overlay driver.
# Without this file, Podman auto-detects fuse-overlayfs (still apk-installed
# as a fallback) and uses it, which is slower than native overlay.
mkdir -p "$ROOTFS/etc/containers"
cp /work/files/etc/containers/storage.conf "$ROOTFS/etc/containers/storage.conf"
chmod 0644 "$ROOTFS/etc/containers/storage.conf"

# Hostname (read by podroid-bootstrap via `hostname -F /etc/hostname`)
echo "podroid" > "$ROOTFS/etc/hostname"
echo "127.0.0.1 localhost podroid" > "$ROOTFS/etc/hosts"
echo "::1 localhost ip6-localhost" >> "$ROOTFS/etc/hosts"

# Login banner shown by getty before the login prompt.
# \S=Alpine release, \r=kernel, \m=arch, \l=tty
cat > "$ROOTFS/etc/issue" <<'EOF'
Welcome to Podroid (Alpine \S)
Kernel \r on \m (\l)

  Default login:  root  /  podroid
  Change root password:    passwd
  Create a regular user:   adduser -G wheel <name>
                           (wheel group → can run doas/sudo)

EOF

# Set runlevels via direct symlinks (host is x86_64, can't chroot into aarch64 rootfs to run rc-update).
# rc-update is just `ln -s /etc/init.d/X /etc/runlevels/<level>/X` under the hood.
mkdir -p "$ROOTFS/etc/runlevels/default" "$ROOTFS/etc/runlevels/boot"
# Guard each link: a dangling symlink (e.g. dnsmasq.lxcbr0, which lxc-bridge
# may ship only as dnsmasq config and not an init script) makes OpenRC log
# an error every boot and stalls podroid-ready's `after *` on a phantom.
for svc in podroid-bootstrap podroid-network podroid-resize dropbear docker lxc dnsmasq.lxcbr0 podroid-x11 podroid-vsock podroid-ready; do
    if [ -e "$ROOTFS/etc/init.d/$svc" ]; then
        ln -sf "/etc/init.d/$svc" "$ROOTFS/etc/runlevels/default/$svc"
    else
        echo "WARN: init script /etc/init.d/$svc missing, skipping runlevel symlink"
    fi
done

# Disable services we don't need (initramfs already handles them, or they're noise in the VM)
for svc in hwclock swclock urandom networking sysctl bootmisc syslog; do
    rm -f "$ROOTFS/etc/runlevels/boot/$svc" "$ROOTFS/etc/runlevels/default/$svc"
done
