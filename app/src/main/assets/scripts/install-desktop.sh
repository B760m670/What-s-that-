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

echo "[install] Profile: $PROFILE"

# Flaky CDN mirrors occasionally serve mismatched index sizes ("Mirror sync in
# progress"). Retry transient fetches, and don't abort the whole install if some
# optional pockets (jammy-updates/security) fail — the base jammy indexes that
# carry XFCE download fine and are enough.
mkdir -p /etc/apt/apt.conf.d
echo 'Acquire::Retries "5";' > /etc/apt/apt.conf.d/80-retries

echo "[install] Updating package lists..."
apt-get update -y || apt-get update -y || \
    echo "[install] apt update had partial errors — continuing with available indexes."

echo "[install] Installing base utilities..."
apt-get install -y --no-install-recommends \
    ca-certificates curl wget nano less sudo \
    locales dbus-x11 tzdata \
    tigervnc-standalone-server tigervnc-common \
    fonts-dejavu-core

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
    echo "[install] Installing everyday tools (browser, files, editor)..."
    apt-get install -y --no-install-recommends \
        firefox-esr mousepad ristretto \
        git python3 python3-pip build-essential \
        htop neofetch || \
        echo "[install] Some optional tools failed — desktop still usable."
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
