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
# Active distro's rootfs (set by the app); fall back to the legacy ubuntu path.
ROOTFS="${WT_ROOTFS:-$WT_HOME/ubuntu}"

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

# Browsers (Firefox/Chromium) need a writable /dev/shm for shared memory; the
# bound Android /dev usually has none, which crashes their content processes
# ("Your tab just crashed"). Provide a real one from app storage.
SHM_DIR="$WT_HOME/tmp/shm"
mkdir -p "$SHM_DIR" 2>/dev/null || true
chmod 1777 "$SHM_DIR" 2>/dev/null || true

# NOTE: `env -i` below is a whitelist, and it is the single place where a
# feature can die silently — a variable the app exports but does not list here
# simply never reaches the guest, and the script inside reads its default
# instead. WT_DISTRO and WT_PKG were in exactly that state: install-desktop.sh
# has always read them, and they have never once arrived. Anything the app adds
# must be added here too.
#
# proot flags (Termux proot):
#   --link2symlink  emulate hardlinks as symlinks — ESSENTIAL: Android's app
#                   filesystem refuses hardlinks, which breaks dpkg/apt (they
#                   create backup hardlinks). proot-distro relies on this too.
#   -0  run as fake root        -r  guest rootfs
#   -w  initial working dir     -b  bind host paths into the guest
# (We deliberately do NOT pass --kill-on-exit: the VNC server must survive
#  after start-desktop.sh's proot invocation returns.)
exec "$PROOT" \
    --link2symlink \
    -0 \
    -r "$ROOTFS" \
    -w /root \
    -b /dev \
    -b "$SHM_DIR:/dev/shm" \
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
        MOZ_DISABLE_CONTENT_SANDBOX=1 \
        MOZ_DISABLE_GMP_SANDBOX=1 \
        MOZ_DISABLE_RDD_SANDBOX=1 \
        WT_PROFILE="${WT_PROFILE:-full}" \
        WT_VARIANT="${WT_VARIANT:-standard}" \
        WT_GEOMETRY="${WT_GEOMETRY:-1280x720}" \
        WT_DISTRO="${WT_DISTRO:-}" \
        WT_PKG="${WT_PKG:-apt}" \
        WT_DE="${WT_DE:-xfce}" \
        WT_SESSION_TYPE="${WT_SESSION_TYPE:-x11}" \
        "$@"
