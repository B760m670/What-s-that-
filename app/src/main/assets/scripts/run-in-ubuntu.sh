#!/system/bin/sh
# run-in-ubuntu.sh — proot launch wrapper.
#
# Enters the Ubuntu rootfs via proot and runs a command (default: login shell).
# This is the single entry point every other action goes through, so the proot
# binding flags live in exactly one place.
#
# Exported by the app: WT_HOME, WT_ARCH, PROOT  (see LinuxEnvironment.kt)
set -eu

: "${WT_HOME:?}" "${PROOT:?}"
ROOTFS="$WT_HOME/ubuntu"

if [ ! -f "$ROOTFS/.bootstrap-done" ]; then
    echo "Ubuntu is not installed yet. Run bootstrap first." >&2
    exit 1
fi

# proot (Termux build) is dynamically linked and uses an external loader. Make
# its shared lib (libtalloc.so.2, via the symlink in WT_LIBDIR) and loader
# discoverable for the host-side proot process. These affect proot itself, not
# the guest (the guest env is wiped by `env -i` below).
if [ -n "${WT_NATIVE_LIB:-}" ]; then
    export LD_LIBRARY_PATH="${WT_LIBDIR:-}:${WT_NATIVE_LIB}${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
    [ -f "$WT_NATIVE_LIB/libloader.so" ]   && export PROOT_LOADER="$WT_NATIVE_LIB/libloader.so"
    [ -f "$WT_NATIVE_LIB/libloader32.so" ] && export PROOT_LOADER_32="$WT_NATIVE_LIB/libloader32.so"
fi

# Flags kept to the subset shared by Termux proot and upstream proot, so the
# same wrapper works on-device and in tests:
#   -0  run as fake root        -r  guest rootfs
#   -w  initial working dir     -b  bind host paths into the guest
# (We deliberately do NOT pass --kill-on-exit: the VNC server must survive
#  after start-desktop.sh's proot invocation returns.)
exec "$PROOT" \
    -0 \
    -r "$ROOTFS" \
    -w /root \
    -b /dev \
    -b /proc \
    -b /sys \
    -b "$WT_HOME/tmp:/tmp" \
    /usr/bin/env -i \
        HOME=/root \
        USER=root \
        TERM="${TERM:-xterm-256color}" \
        LANG=C.UTF-8 \
        PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
        DISPLAY=:1 \
        WT_PROFILE="${WT_PROFILE:-full}" \
        WT_VARIANT="${WT_VARIANT:-standard}" \
        WT_GEOMETRY="${WT_GEOMETRY:-1280x720}" \
        "$@"
