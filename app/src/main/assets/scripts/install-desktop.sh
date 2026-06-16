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

# ---- Alpine (apk / musl, lightest) --------------------------------------
if [ "$DISTRO_ID" = "alpine" ]; then
    echo "[install] Alpine detected — installing XFCE via apk…"
    # XFCE lives in the 'community' repo. Enable it matching the image's own
    # main repo; if the minirootfs ships without repos, write sane defaults.
    mkdir -p /etc/apk
    if grep -q '/main' /etc/apk/repositories 2>/dev/null; then
        sed -i 's|^#\(.*/community\)$|\1|' /etc/apk/repositories || true
        if ! grep -q '/community' /etc/apk/repositories 2>/dev/null; then
            MAIN=$(grep -m1 '/main$' /etc/apk/repositories)
            echo "${MAIN%/main}/community" >> /etc/apk/repositories
        fi
    else
        printf '%s\n%s\n' \
            "https://dl-cdn.alpinelinux.org/alpine/latest-stable/main" \
            "https://dl-cdn.alpinelinux.org/alpine/latest-stable/community" \
            > /etc/apk/repositories
    fi
    apk update || apk update || echo "[install] apk update had errors — continuing."
    apk add --no-cache \
        dbus dbus-x11 tigervnc \
        xfce4 xfce4-terminal \
        adwaita-icon-theme hicolor-icon-theme gdk-pixbuf librsvg \
        ttf-dejavu \
        bash sudo nano shadow setpriv \
        || echo "[install] some Alpine packages failed — desktop may still work."
    touch /root/.wt-desktop-ready
    echo "[install] Desktop installed (alpine)."
    exit 0
fi

# ---- Ubuntu / Debian (apt) ----------------------------------------------
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

touch /root/.wt-desktop-ready
echo "[install] Desktop installed (profile: $PROFILE)."
