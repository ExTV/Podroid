#!/bin/bash
# Runs inside the uml-builder image. Fetches the pinned UML arm64 tree,
# applies Podroid's patch, builds the 4K and 16K kernels plus stubs, enforces
# the critical-option check and packages the GPL source tarball into /out.
set -euo pipefail

: "${UML_COMMIT:?UML_COMMIT is required}"
: "${NDK:?NDK is required}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_URL=https://github.com/zalexdev/linux-um-arm64
TREE=/work/linux
OUT=/out
STRIP="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
mkdir -p "$OUT" "$TREE"

# GitHub only serves full object names, so expand an abbreviated pin through
# the advertised refs.
full="$UML_COMMIT"
if [ "${#full}" -lt 40 ]; then
    full=$(git ls-remote "$REPO_URL" | awk -v p="$UML_COMMIT" 'index($1, p) == 1 { print $1; exit }')
    [ -n "$full" ] || { echo "cannot expand $UML_COMMIT to a full commit id" >&2; exit 1; }
fi
cd "$TREE"
git init -q
git fetch -q --depth 1 "$REPO_URL" "$full"
git checkout -q FETCH_HEAD
echo "UML tree at $(git rev-parse HEAD)"
git apply "$HERE/kernel/memfd-physmem.patch"
git apply "$HERE/kernel/hostfs-confine.patch"

KDIR="$HERE/kernel"
BASE_FRAGS="config/docker.config config/smp.config $KDIR/netfilter.config $KDIR/podroid-parity.config $KDIR/podroid-uml-extra.config"

# The stub is linked with -n (no page alignment), so its PT_LOAD p_align is 8
# and -z max-page-size has no effect on it. It is exec'd by the kernel at a
# fixed ET_EXEC address (stub vaddr 0x2000e8, file offset 0xe8), never
# dlopen'd, and vaddr is already congruent to offset mod 16384, so raising
# p_align is metadata only and the layout is unchanged. It satisfies the 16KB
# packaging check.
raise_stub_align() {
    python3 - "$1" <<'PYEOF'
import struct, sys
path = sys.argv[1]
d = bytearray(open(path, 'rb').read())
phoff = struct.unpack_from('<Q', d, 32)[0]
phentsize = struct.unpack_from('<H', d, 54)[0]
phnum = struct.unpack_from('<H', d, 56)[0]
for i in range(phnum):
    off = phoff + i * phentsize
    if struct.unpack_from('<I', d, off)[0] != 1:
        continue
    vaddr = struct.unpack_from('<Q', d, off + 16)[0]
    foff = struct.unpack_from('<Q', d, off + 8)[0]
    if (vaddr - foff) % 16384 != 0:
        sys.exit("stub segment vaddr/offset not congruent mod 16384")
    struct.pack_into('<Q', d, off + 48, 16384)
open(path, 'wb').write(d)
PYEOF
}

build_variant() {
    local variant="$1" pagecfg="$2" pageopt="$3"
    local obj=/work/obj-$variant art=/work/art-$variant
    echo "==> Building UML kernel $variant"
    ( cd "$TREE/tools/um-arm64" && \
      ART="$art" O="$obj" \
      EXTRA_CONFIG="$BASE_FRAGS $KDIR/$pagecfg" \
      bash harness/build-bionic.sh )

    echo "==> Checking critical options ($variant)"
    local missing=0 opt
    for opt in $(grep -vE '^[[:space:]]*(#|$)' "$HERE/critical-options.txt") "$pageopt"; do
        if ! grep -qx "$opt=y" "$obj/.config"; then
            echo "CRITICAL OPTION NOT =y in $variant: $opt" >&2
            missing=1
        fi
    done
    [ "$missing" = 0 ] || exit 1

    cp "$art/linux-bionic" "$OUT/libuml-$variant.so"
    cp "$art/stub_exe_bionic" "$OUT/libumlstub-$variant.so"
    "$STRIP" "$OUT/libuml-$variant.so" "$OUT/libumlstub-$variant.so"
    raise_stub_align "$OUT/libumlstub-$variant.so"
}

build_variant 4k page4k.config CONFIG_PAGE_SIZE_4KB
build_variant 16k page16k.config CONFIG_PAGE_SIZE_16KB

echo "==> Packaging source tarball"
SRC=/work/src-stage
mkdir -p "$SRC"
rsync -a --exclude=.git "$TREE/" "$SRC/"
mkdir -p "$SRC/podroid-build-uml"
cp -r "$HERE/kernel" "$HERE/critical-options.txt" "$HERE/build-uml.sh" "$SRC/podroid-build-uml/"
tar -C "$SRC" -cf - . | zstd -19 -T0 -q -o "$OUT/uml-source-$UML_COMMIT.tar.zst"
ls -l "$OUT"
