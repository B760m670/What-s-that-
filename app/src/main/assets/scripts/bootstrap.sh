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

# Ubuntu 22.04 LTS rootfs from cloud-images.ubuntu.com.
#   standard — full server rootfs (full profile)
#   minimal  — stripped image (~30% smaller) for the lite profile / weak phones
UBUNTU_VERSION="22.04"
UBUNTU_RELEASE="jammy"
VARIANT="${WT_VARIANT:-standard}"
case "$WT_ARCH" in
    aarch64) IMG_ARCH="arm64" ;;
    arm)     IMG_ARCH="armhf" ;;
    x86_64)  IMG_ARCH="amd64" ;;
    *) echo "Unsupported arch: $WT_ARCH" >&2; exit 2 ;;
esac
if [ "$VARIANT" = "minimal" ]; then
    ROOTFS_URL="https://cloud-images.ubuntu.com/minimal/releases/${UBUNTU_RELEASE}/release/ubuntu-${UBUNTU_VERSION}-minimal-cloudimg-${IMG_ARCH}-root.tar.xz"
else
    ROOTFS_URL="https://cloud-images.ubuntu.com/releases/${UBUNTU_VERSION}/release/ubuntu-${UBUNTU_VERSION}-server-cloudimg-${IMG_ARCH}-root.tar.xz"
fi

log() { echo "[bootstrap] $*"; }

if [ -f "$STAMP" ]; then
    log "Ubuntu rootfs already installed — nothing to do."
    exit 0
fi

mkdir -p "$ROOTFS" "$TMP"

TARBALL="$TMP/ubuntu-rootfs.tar.xz"
if [ ! -s "$TARBALL" ]; then
    log "Downloading Ubuntu $UBUNTU_RELEASE $VARIANT ($IMG_ARCH) rootfs..."
    # -C - resumes a partial download if the app retries.
    curl -L --fail --retry 3 -C - -o "$TARBALL" "$ROOTFS_URL"
fi

log "Extracting rootfs (this runs once)..."
# proot --link2symlink lets us unpack as non-root while keeping hardlink/owner
# metadata; tar auto-detects the xz compression. Fall back to plain tar.
"$PROOT" --link2symlink tar -xf "$TARBALL" -C "$ROOTFS" --exclude='dev/*' 2>/dev/null || \
    tar -xf "$TARBALL" -C "$ROOTFS" --exclude='dev/*'

# A working resolv.conf so apt/DNS works inside the container. Cloud images
# ship /etc/resolv.conf as a dangling symlink into systemd-resolved, so remove
# it first and write a real file.
mkdir -p "$ROOTFS/etc"
rm -f "$ROOTFS/etc/resolv.conf" "$ROOTFS/etc/hosts"
printf 'nameserver 8.8.8.8\nnameserver 1.1.1.1\n' > "$ROOTFS/etc/resolv.conf"
printf '127.0.0.1 localhost\n' > "$ROOTFS/etc/hosts"

rm -f "$TARBALL"
touch "$STAMP"
log "Ubuntu rootfs ready at $ROOTFS"
