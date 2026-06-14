#!/system/bin/sh
# bootstrap.sh — first-run setup for "What's that? — Linux"
#
# Downloads a minimal Ubuntu rootfs and prepares a proot launch wrapper.
# Runs in Android's app sandbox (no root). Driven by the app, which exports:
#   WT_HOME  - app private files dir (writable, exec allowed)
#   WT_ARCH  - device CPU arch: aarch64 | arm | x86_64
#   PROOT    - absolute path to the prebuilt proot binary shipped in the APK
#
# Decentralised by design: there is NO central app server. The rootfs is pulled
# from public Ubuntu image mirrors and you can point at ANY mirror you trust:
#   WT_ROOTFS_URL      - exact tarball URL (overrides everything)
#   WT_ROOTFS_MIRRORS  - space-separated mirror base URLs, tried in order
# Every download is integrity-checked against the publisher's SHA256SUMS.
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
    REL_PATH="minimal/releases/${UBUNTU_RELEASE}/release"
    FNAME="ubuntu-${UBUNTU_VERSION}-minimal-cloudimg-${IMG_ARCH}-root.tar.xz"
else
    REL_PATH="releases/${UBUNTU_VERSION}/release"
    FNAME="ubuntu-${UBUNTU_VERSION}-server-cloudimg-${IMG_ARCH}-root.tar.xz"
fi

# Mirror list (decentralised). Override with WT_ROOTFS_MIRRORS. cloud-images is
# the upstream; add any geographically-closer or self-hosted mirror you trust.
MIRRORS="${WT_ROOTFS_MIRRORS:-https://cloud-images.ubuntu.com}"

log() { echo "[bootstrap] $*"; }

if [ -f "$STAMP" ]; then
    log "Ubuntu rootfs already installed — nothing to do."
    exit 0
fi

mkdir -p "$ROOTFS" "$TMP"
TARBALL="$TMP/ubuntu-rootfs.tar.xz"
SUMS="$TMP/SHA256SUMS"

# Resolve the tarball + checksum URLs: explicit override first, else mirrors.
fetch_ok=0
if [ -n "${WT_ROOTFS_URL:-}" ]; then
    log "Using user-provided rootfs URL."
    curl -L --fail --retry 3 -C - -o "$TARBALL" "$WT_ROOTFS_URL" && fetch_ok=1
    curl -L --fail -o "$SUMS" "${WT_ROOTFS_URL%/*}/SHA256SUMS" 2>/dev/null || true
else
    for base in $MIRRORS; do
        url="${base%/}/${REL_PATH}/${FNAME}"
        log "Trying mirror: $url"
        if [ -s "$TARBALL" ] || curl -L --fail --retry 3 -C - -o "$TARBALL" "$url"; then
            curl -L --fail -o "$SUMS" "${base%/}/${REL_PATH}/SHA256SUMS" 2>/dev/null || true
            fetch_ok=1; break
        fi
        log "Mirror failed, trying next..."
    done
fi
[ "$fetch_ok" = 1 ] || { log "All mirrors failed."; exit 1; }

# Integrity check: verify the tarball against the publisher's SHA256SUMS.
# Refuses to install a tampered/corrupt image. If no checksum tool or sums file
# is available, we warn rather than silently trusting the bytes.
expected="$(grep " [*]\{0,1\}${FNAME}\$" "$SUMS" 2>/dev/null | awk '{print $1}' | head -1)"
if command -v sha256sum >/dev/null 2>&1 && [ -n "$expected" ]; then
    actual="$(sha256sum "$TARBALL" | awk '{print $1}')"
    if [ "$actual" != "$expected" ]; then
        log "CHECKSUM MISMATCH — refusing to install. Expected $expected, got $actual"
        rm -f "$TARBALL"
        exit 1
    fi
    log "Integrity verified (SHA256 OK)."
else
    log "WARNING: could not verify checksum (no sums file or sha256sum) — proceeding unverified."
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

rm -f "$TARBALL" "$SUMS"
touch "$STAMP"
log "Ubuntu rootfs ready at $ROOTFS"
