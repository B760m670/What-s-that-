#!/bin/bash
# install-desktop.sh — runs INSIDE the Ubuntu container (via run-in-ubuntu.sh).
#
# Installs a lightweight-but-complete XFCE desktop plus a VNC server and a
# curated set of everyday tools. Kept lean on purpose: XFCE core + goodies,
# not the full ubuntu-desktop metapackage (which would pull ~2GB of services
# that can't run under proot anyway).
set -eu
export DEBIAN_FRONTEND=noninteractive

echo "[install] Updating package lists..."
apt-get update -y

echo "[install] Installing base utilities..."
apt-get install -y --no-install-recommends \
    ca-certificates curl wget nano vim less sudo \
    locales dbus-x11 tzdata

# Generate a UTF-8 locale so XFCE/apps render correctly.
locale-gen en_US.UTF-8 || true

echo "[install] Installing XFCE desktop (lightweight set)..."
apt-get install -y --no-install-recommends \
    xfce4 xfce4-terminal xfce4-goodies \
    tigervnc-standalone-server tigervnc-common \
    fonts-dejavu-core

echo "[install] Installing everyday tools (browser, files, editor)..."
apt-get install -y --no-install-recommends \
    firefox-esr \
    mousepad ristretto \
    git python3 python3-pip build-essential \
    htop neofetch || \
    echo "[install] Some optional tools failed — desktop still usable."

echo "[install] Cleaning apt caches to keep the install light..."
apt-get clean
rm -rf /var/lib/apt/lists/*

# Pre-seed a VNC startup so the desktop launches on `start-desktop.sh`.
mkdir -p /root/.vnc
cat > /root/.vnc/xstartup <<'EOF'
#!/bin/bash
unset SESSION_MANAGER
unset DBUS_SESSION_BUS_ADDRESS
export XKL_XMODMAP_DISABLE=1
[ -r /etc/X11/Xresources ] && xrdb /etc/X11/Xresources
dbus-launch --exit-with-session startxfce4
EOF
chmod +x /root/.vnc/xstartup

echo "[install] XFCE desktop installed."
