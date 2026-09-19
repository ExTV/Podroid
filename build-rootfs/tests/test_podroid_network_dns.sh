#!/bin/sh
# Regression tests for QEMU guest DNS resolver ordering.
set -eu

ROOT=$(cd "$(dirname "$0")/.." && pwd)
# shellcheck disable=SC1091
. "$ROOT/files/etc/init.d/podroid-network"

assert_resolvers() {
    actual=$(qemu_resolv_conf "$1")
    if [ "$actual" != "$2" ]; then
        echo "FAIL: DNS input: $1" >&2
        printf 'expected:\n%s' "$2" >&2
        printf 'actual:\n%s\n' "$actual" >&2
        exit 1
    fi
}

# Invalid dotted quads are rejected without discarding later valid entries.
assert_resolvers '999.1.1.1,1.2.3,127.0.0.1,0.0.0.0,192.168.1.1' \
    'nameserver 192.168.1.1
nameserver 8.8.8.8
nameserver 1.1.1.1'

# Trailing dots and leading-zero octets are not accepted as IPv4 literals.
assert_resolvers '192.168.1.1.,10.0.0.1' \
    'nameserver 10.0.0.1
nameserver 8.8.8.8
nameserver 1.1.1.1'
assert_resolvers '192.168.001.1,10.0.0.2' \
    'nameserver 10.0.0.2
nameserver 8.8.8.8
nameserver 1.1.1.1'

# IPv6 and shell metacharacters are rejected without being evaluated.
assert_resolvers '2001:db8::1,1.2.3.4;touch /tmp/podroid-dns' \
    'nameserver 10.0.2.3
nameserver 8.8.8.8
nameserver 1.1.1.1'

# Valid entries retain order and duplicates do not consume a resolver slot.
assert_resolvers '192.168.1.2,192.168.1.2,192.168.1.1' \
    'nameserver 192.168.1.2
nameserver 192.168.1.1
nameserver 8.8.8.8'

# One device resolver is followed by both public fallbacks.
assert_resolvers '192.168.1.1' \
    'nameserver 192.168.1.1
nameserver 8.8.8.8
nameserver 1.1.1.1'

# Two device resolvers precede the first non-duplicate fallback.
assert_resolvers '192.168.1.1,192.168.1.2' \
    'nameserver 192.168.1.1
nameserver 192.168.1.2
nameserver 8.8.8.8'

# No device DNS preserves the original SLIRP/public fallback behavior.
assert_resolvers '' \
    'nameserver 10.0.2.3
nameserver 8.8.8.8
nameserver 1.1.1.1'

# Public fallback duplicates are removed and the result never exceeds musl's
# three nameserver slots, even when more device values are supplied.
assert_resolvers '8.8.8.8,8.8.8.8,1.1.1.1' \
    'nameserver 8.8.8.8
nameserver 1.1.1.1'
assert_resolvers '10.0.0.1,10.0.0.2,10.0.0.3,8.8.8.8' \
    'nameserver 10.0.0.1
nameserver 10.0.0.2
nameserver 8.8.8.8'

echo "PASS"
