#!/bin/sh
# podroid-vsock-agent no longer rewrites forwards.conf on ADD/REMOVE (runtime
# forwards are ephemeral now: Android replays every rule over the ctl channel
# on each boot), so a copy shadowing the shipped seed in the persistent upper
# is leftover state from before this fix. Remove it directly from the upper
# layer: deleting through the overlay union would whiteout the lower (shipped)
# copy instead of just clearing the shadow.
set -eu

rm -f /mnt/persist/upper/etc/podroid/forwards.conf

exit 0
