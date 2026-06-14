#!/system/bin/sh
# bootstrap.sh — first-run setup for "What's that? — Linux"
#
# Downloads a minimal Ubuntu rootfs and prepares a proot launch wrapper.
# Runs in Android's app sandbox (no root). Driven by the app, which exports:
#   WT_HOME  - app private files dir (writable, exec allowed)
#   WT_ARCH  - device CPU arch: aarch64 | arm | x86_64
#   PROOT    - absolute path to the prebuilt proot binary shipped in the APK
#
# It is idempotent: re-running after a partial setup resumes safely.
set -eu

: "${WT_HOME:?WT_HOME must be set by the app}"
: "${WT_ARCH:?WT_ARCH must be set by the app}"
: "${PROOT:?PROOT must be set by the app}"

ROOTFS="$WT_HOME/ubuntu"
TMP="$WT_HOME/tmp"
STAMP="$ROOTFS/.bootstrap-done"

# Ubuntu base images published for proot/container use (Ubuntu 22.04 LTS, ~30MB).
UBUNTU_RELEASE="jammy"
case "$WT_ARCH" in
    aarch64) IMG_ARCH="arm64" ;;
    arm)     IMG_ARCH="armhf" ;;
    x86_64)  IMG_ARCH="amd64" ;;
    *) echo "Unsupported arch: $WT_ARCH" >&2; exit 2 ;;
esac
ROOTFS_URL="https://partner-images.canonical.com/core/${UBUNTU_RELEASE}/current/ubuntu-${UBUNTU_RELEASE}-core-cloudimg-${IMG_ARCH}-root.tar.gz"

log() { echo "[bootstrap] $*"; }

if [ -f "$STAMP" ]; then
    log "Ubuntu rootfs already installed — nothing to do."
    exit 0
fi

mkdir -p "$ROOTFS" "$TMP"

TARBALL="$TMP/ubuntu-rootfs.tar.gz"
if [ ! -s "$TARBALL" ]; then
    log "Downloading Ubuntu $UBUNTU_RELEASE ($IMG_ARCH) base image..."
    # -C - resumes a partial download if the app retries.
    curl -L --fail --retry 3 -C - -o "$TARBALL" "$ROOTFS_URL"
fi

log "Extracting rootfs (this runs once)..."
# proot lets us extract as non-root while preserving ownership metadata.
"$PROOT" --link2symlink tar -xzf "$TARBALL" -C "$ROOTFS" --exclude='dev' 2>/dev/null || \
    tar -xzf "$TARBALL" -C "$ROOTFS" --exclude='dev'

# A working resolv.conf so apt/DNS works inside the container.
mkdir -p "$ROOTFS/etc"
printf 'nameserver 8.8.8.8\nnameserver 1.1.1.1\n' > "$ROOTFS/etc/resolv.conf"
# Some images ship resolv.conf as a dangling symlink; force a real file.
printf '127.0.0.1 localhost\n' > "$ROOTFS/etc/hosts"

rm -f "$TARBALL"
touch "$STAMP"
log "Ubuntu rootfs ready at $ROOTFS"
