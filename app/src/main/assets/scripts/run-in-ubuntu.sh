#!/system/bin/sh
# run-in-ubuntu.sh — proot launch wrapper.
#
# Enters the Ubuntu rootfs via proot and runs a command (default: login shell).
# This is the single entry point every other action goes through, so the proot
# binding flags live in exactly one place.
#
# Exported by the app: WT_HOME, WT_ARCH, PROOT  (see bootstrap.sh)
set -eu

: "${WT_HOME:?}" "${PROOT:?}"
ROOTFS="$WT_HOME/ubuntu"

if [ ! -f "$ROOTFS/.bootstrap-done" ]; then
    echo "Ubuntu is not installed yet. Run bootstrap first." >&2
    exit 1
fi

# Android blocks ptrace by default for sibling processes; proot needs this to
# be tolerant, hence -L (link2symlink) and kill-on-exit for clean teardown.
exec "$PROOT" \
    --kill-on-exit \
    --root-id \
    --rootfs="$ROOTFS" \
    --cwd=/root \
    --bind=/dev \
    --bind=/proc \
    --bind=/sys \
    --bind="$WT_HOME/tmp:/tmp" \
    /usr/bin/env -i \
        HOME=/root \
        USER=root \
        TERM="${TERM:-xterm-256color}" \
        LANG=C.UTF-8 \
        PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
        DISPLAY=:1 \
        "$@"
