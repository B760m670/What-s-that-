#!/bin/bash
# install-desktop.sh — runs INSIDE the container (via run-in-ubuntu.sh).
#
# Installs a desktop plus a VNC server. Which desktop is the app's choice
# (WT_DE), and installing one never removes another: a rootfs can hold XFCE and
# GNOME at the same time, and switching between them is then a relaunch rather
# than a re-download. That is the whole reason the desktop is modelled as
# packages inside a distro instead of as a distro of its own.
#
#   xfce   — the default. A complete desktop that still runs acceptably here.
#   gnome  — the real GNOME desktop: the shell, the settings daemon, and the
#            applications that make it a desktop rather than a shell. Much
#            heavier, and on a CPU rasteriser it will feel it.
#
# WT_PROFILE picks how much goes on top:
#   full — browser and dev tools. For capable phones.
#   lite — the desktop and nothing else, for old/weak devices.
#
# We avoid the ubuntu-desktop / gnome-core metapackages either way: both drag in
# gdm3 and a pile of systemd services that cannot run under proot.
set -eu
export DEBIAN_FRONTEND=noninteractive
PROFILE="${WT_PROFILE:-full}"
DE="${WT_DE:-xfce}"

# The app parses these markers to show a real progress bar. apt gives no usable
# overall percentage across a dozen separate invocations, but the sequence of
# steps here is fixed and known, so counting them is honest and legible.
# Keep the totals in step with the step() calls on each branch below.
STEP=0
if [ "$DE" = "gnome" ]; then
    if [ "$PROFILE" = "lite" ]; then TOTAL_STEPS=6; else TOTAL_STEPS=8; fi
else
    if [ "$PROFILE" = "lite" ]; then TOTAL_STEPS=5; else TOTAL_STEPS=7; fi
fi
step() {
    STEP=$((STEP + 1))
    echo "[step] $STEP/$TOTAL_STEPS $1"
}

# Trust the rootfs itself, not what the app thinks is active — that's the single
# source of truth and avoids writing the wrong distro's repos.
DISTRO_ID="unknown"
PRETTY="unknown"
CODENAME=""
if [ -r /etc/os-release ]; then
    . /etc/os-release
    DISTRO_ID="${ID:-unknown}"
    PRETTY="${PRETTY_NAME:-$DISTRO_ID}"
    CODENAME="${VERSION_CODENAME:-}"
fi
echo "[install] Desktop: $DE  Profile: $PROFILE  Rootfs: $PRETTY  (app said: ${WT_DISTRO:-?})"

step "Preparing package sources"

# Only Ubuntu's cloud image ships a stale/edited sources.list that needs a full
# rewrite. Debian (and other images) already have a correct, signed sources.list
# with the matching keyring, so leave theirs untouched.
#
# The codename comes from the rootfs, never from a constant here: this file used
# to hardcode "jammy", which silently pointed a newer image at an older
# release's repositories the moment the base image was updated.
if [ "$DISTRO_ID" = "ubuntu" ] && [ -n "$CODENAME" ]; then
    case "$(dpkg --print-architecture)" in
        arm64|armhf) MIRROR="http://ports.ubuntu.com/ubuntu-ports" ;;
        *)           MIRROR="http://archive.ubuntu.com/ubuntu" ;;
    esac
    cat > /etc/apt/sources.list <<EOF
deb $MIRROR $CODENAME main restricted universe multiverse
deb $MIRROR $CODENAME-updates main restricted universe multiverse
deb $MIRROR $CODENAME-security main restricted universe multiverse
deb $MIRROR $CODENAME-backports main restricted universe multiverse
EOF
    # Newer Ubuntu images carry the deb822 list as well; two sources for the
    # same suite make apt complain about duplicates on every run.
    rm -f /etc/apt/sources.list.d/ubuntu.sources 2>/dev/null || true
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

step "Updating package lists"
apt-get update -y || apt-get update -y || apt-get update -y || \
    echo "[install] apt update had partial errors — continuing with available indexes."

rm -f /etc/apt/apt.conf.d/81-nocache   # package downloads may use the fast CDN cache

# Recover from any prior interrupted install (e.g. a run before --link2symlink
# fixed dpkg's hardlink failures left packages half-unpacked).
dpkg --configure -a || true
apt-get install -f -y || true

step "Installing base utilities"
apt-get install -y --no-install-recommends \
    ca-certificates curl wget nano less sudo procps \
    locales dbus-x11 tzdata \
    tigervnc-standalone-server tigervnc-common \
    pulseaudio pulseaudio-utils \
    fonts-dejavu-core

# Generate a UTF-8 locale so apps render correctly.
locale-gen en_US.UTF-8 || true

# Install packages one at a time, reporting each failure but never letting one
# missing package take the rest down with it. Names drift between Debian and
# Ubuntu releases (eog became loupe, gnome-terminal is being replaced), and a
# single apt invocation is all-or-nothing.
# apt's own output is deliberately NOT swallowed: it is the only sign of life
# during a ten-minute install, and the app shows the newest line on the card.
try_install() {
    local ok=0 p
    for p in "$@"; do
        if apt-get install -y --no-install-recommends "$p"; then
            ok=1
        else
            echo "[install]   ! $p is not available here — skipped"
        fi
    done
    return $((1 - ok))
}

# Try each candidate in turn, stop at the first that installs.
try_first() {
    local p
    for p in "$@"; do
        if apt-get install -y --no-install-recommends "$p"; then
            echo "[install]   using $p"
            return 0
        fi
    done
    return 1
}

if [ "$DE" = "gnome" ]; then
    step "Installing GNOME"
    # The core the session cannot start without. gnome-shell pulls
    # gnome-settings-daemon and mutter itself; the rest is what we add
    # deliberately:
    #   gnome-session-bin  the session manager binary. We supply our own
    #                      .session file at launch, so the distro-specific
    #                      metapackages (gnome-session on Debian, ubuntu-session
    #                      on Ubuntu) are not needed and not wanted.
    #   xwayland           X11 applications inside a nested Wayland session.
    #   libgl1-mesa-dri    llvmpipe. Without it there is no GL at all behind
    #                      Xvnc and mutter cannot start.
    #   dconf-cli          `dconf update`, which is how the launch script sets
    #                      defaults before a session bus exists.
    if ! apt-get install -y --no-install-recommends \
            gnome-shell gnome-session-bin gnome-settings-daemon \
            xwayland libgl1-mesa-dri dconf-cli gsettings-desktop-schemas; then
        echo "[install] GNOME could not be installed. Falling back to XFCE."
        DE=xfce
        apt-get install -y --no-install-recommends xfce4 xfce4-terminal
    fi
fi

if [ "$DE" = "gnome" ]; then
    step "Installing GNOME applications"
    # A shell on its own is not a desktop. These are what make it one — files,
    # a terminal, an editor, settings, an archive tool, a monitor.
    try_install nautilus gnome-text-editor gnome-system-monitor \
                gnome-control-center file-roller gnome-calculator || true
    try_first gnome-terminal gnome-console ptyxis xfce4-terminal || \
        echo "[install]   ! no terminal emulator available"
    try_first loupe eog gnome-photos || \
        echo "[install]   ! no image viewer available"
elif [ "$PROFILE" = "lite" ]; then
    step "Installing the lite desktop"
    apt-get install -y --no-install-recommends \
        openbox obconf tint2 xterm pcmanfm \
        || echo "[install] Some lite extras failed — desktop still usable."
else
    step "Installing the XFCE desktop"
    apt-get install -y --no-install-recommends \
        xfce4 xfce4-terminal xfce4-goodies
fi

if [ "$PROFILE" != "lite" ]; then
    step "Installing everyday tools"
    try_install mousepad git python3 python3-pip htop || true

    # Browser: installed on its OWN so a missing package can't sink the tools
    # above. firefox-esr only exists on Debian (Ubuntu ships Firefox as a snap,
    # which can't run under proot), so fall back to a real .deb browser that
    # exists on whichever distro this is.
    step "Installing a web browser"
    if try_first firefox-esr epiphany-browser falkon midori netsurf-gtk; then
        update-alternatives --quiet --auto x-www-browser 2>/dev/null || true
    else
        echo "[install] No browser available — add one later via the console."
    fi
fi

step "Cleaning up"
apt-get clean
rm -rf /var/lib/apt/lists/*

# The launch script writes ~/.vnc/xstartup itself on every start, so that fixes
# reach an already-installed system without reinstalling it. Nothing to seed here.
echo "[install] $DE installed (profile: $PROFILE)."
