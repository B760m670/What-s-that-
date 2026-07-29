#!/bin/bash
# install-desktop.sh — runs INSIDE the Ubuntu container (via run-in-ubuntu.sh).
#
# Installs a desktop + VNC server. Two profiles, selected by WT_PROFILE
# (exported by the app from the build flavor):
#   full — XFCE core + goodies, browser, dev tools. For capable phones.
#   lite — Openbox + a panel, terminal and file manager only. Tiny footprint
#          for old/weak Android 5-era devices.
# Either way we avoid the ubuntu-desktop metapackage, which drags in ~2GB of
# systemd services that can't run under proot.
set -eu
export DEBIAN_FRONTEND=noninteractive
PROFILE="${WT_PROFILE:-full}"

# Trust the rootfs itself, not what the app thinks is active — that's the single
# source of truth and avoids writing the wrong distro's repos.
DISTRO_ID="unknown"
PRETTY="unknown"
if [ -r /etc/os-release ]; then
    . /etc/os-release
    DISTRO_ID="${ID:-unknown}"
    PRETTY="${PRETTY_NAME:-$DISTRO_ID}"
fi
echo "[install] Profile: $PROFILE  Rootfs: $PRETTY  (app said: ${WT_DISTRO:-?})"

# Only Ubuntu's cloud image ships a stale/edited sources.list that needs a full
# rewrite. Debian (and other images) already have a correct, signed sources.list
# with the matching keyring, so leave theirs untouched.
if [ "$DISTRO_ID" = "ubuntu" ]; then
    case "$(dpkg --print-architecture)" in
        arm64|armhf) MIRROR="http://ports.ubuntu.com/ubuntu-ports" ;;
        *)           MIRROR="http://archive.ubuntu.com/ubuntu" ;;
    esac
    cat > /etc/apt/sources.list <<EOF
deb $MIRROR jammy main restricted universe multiverse
deb $MIRROR jammy-updates main restricted universe multiverse
deb $MIRROR jammy-security main restricted universe multiverse
deb $MIRROR jammy-backports main restricted universe multiverse
EOF
fi

# The cloud image's pre-seeded apt lists are weeks old; the CDN edge caches a
# stale InRelease against newer indexes ("File has unexpected size"). Drop the
# lists and force origin revalidation FOR THE INDEX UPDATE ONLY (No-Cache). We
# remove that override before installing so the package .debs (immutable,
# content-addressed) still download fast via the CDN cache.
rm -rf /var/lib/apt/lists/*
mkdir -p /etc/apt/apt.conf.d
echo 'Acquire::Retries "5";' > /etc/apt/apt.conf.d/80-retries
echo 'Acquire::http::No-Cache "true";' > /etc/apt/apt.conf.d/81-nocache

echo "[install] Updating package lists..."
apt-get update -y || apt-get update -y || apt-get update -y || \
    echo "[install] apt update had partial errors — continuing with available indexes."

rm -f /etc/apt/apt.conf.d/81-nocache   # package downloads may use the fast CDN cache

# Recover from any prior interrupted install (e.g. a run before --link2symlink
# fixed dpkg's hardlink failures left packages half-unpacked).
dpkg --configure -a || true
apt-get install -f -y || true

echo "[install] Installing base utilities..."
apt-get install -y --no-install-recommends \
    ca-certificates curl wget nano less sudo procps \
    locales dbus-x11 tzdata \
    tigervnc-standalone-server tigervnc-common \
    xvfb \
    pulseaudio pulseaudio-utils \
    fonts-dejavu-core

# Mesa's DRI drivers, including `virgl` — the client half of GPU acceleration.
# It renders nothing itself: it serialises GL onto the socket held by the
# host-side server (see GpuBridge.kt), which is the only process on the device
# that can reach the vendor driver. Without this package the guest has no virgl
# and silently stays on llvmpipe, so install it on its own and say so if it
# fails. mesa-utils is what makes the difference checkable on-device:
# `glxinfo -B` names the renderer, `glxgears` shows whether it moves.
echo "[install] Installing Mesa (virgl GPU passthrough + GL diagnostics)..."
apt-get install -y --no-install-recommends \
    libgl1-mesa-dri libglx-mesa0 mesa-utils || \
    echo "[install] Mesa install failed — desktop will be software-rendered."

# Generate a UTF-8 locale so apps render correctly.
locale-gen en_US.UTF-8 || true

if [ "$PROFILE" = "lite" ]; then
    echo "[install] Installing lite desktop (Openbox)..."
    apt-get install -y --no-install-recommends \
        openbox obconf tint2 \
        xterm pcmanfm \
        nano || echo "[install] Some lite extras failed — desktop still usable."
    SESSION_CMD="openbox-session"
else
    echo "[install] Installing XFCE desktop..."
    apt-get install -y --no-install-recommends \
        xfce4 xfce4-terminal xfce4-goodies
    echo "[install] Installing everyday tools (files, editor, dev)..."
    apt-get install -y --no-install-recommends \
        mousepad ristretto \
        git python3 python3-pip build-essential \
        htop neofetch || \
        echo "[install] Some optional tools failed — desktop still usable."

    # Browser: install it on its OWN so a missing package can't sink the tools
    # above. firefox-esr only exists on Debian (Ubuntu ships Firefox as a snap,
    # which can't run under proot), so fall back to a real .deb browser that
    # exists on whichever distro this is.
    echo "[install] Installing a web browser..."
    BROWSER_OK=0
    for b in firefox-esr epiphany-browser falkon midori netsurf-gtk; do
        if apt-get install -y --no-install-recommends "$b"; then
            echo "[install] Browser installed: $b"
            update-alternatives --quiet --set x-www-browser "/usr/bin/$b" 2>/dev/null || true
            BROWSER_OK=1
            break
        fi
    done
    [ "$BROWSER_OK" = 1 ] || echo "[install] No browser available — add one later via the console."
    SESSION_CMD="startxfce4"
fi

echo "[install] Cleaning apt caches to keep the install light..."
apt-get clean
rm -rf /var/lib/apt/lists/*

# Pre-seed a VNC startup so the desktop launches on `start-desktop.sh`.
mkdir -p /root/.vnc
cat > /root/.vnc/xstartup <<EOF
#!/bin/bash
unset SESSION_MANAGER
unset DBUS_SESSION_BUS_ADDRESS
export XKL_XMODMAP_DISABLE=1
[ -r /etc/X11/Xresources ] && xrdb /etc/X11/Xresources
dbus-launch --exit-with-session $SESSION_CMD
EOF
chmod +x /root/.vnc/xstartup

echo "[install] Desktop installed (profile: $PROFILE)."
