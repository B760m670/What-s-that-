#!/bin/bash
# install-wine.sh — set up a Windows-apps environment (Box64 + Wine) on Debian.
# Phase 1: prove the CPU layer (Box64 runs x86-64) and get Wine installed. Runs
# inside the container; deliberately tolerant so the full log is visible.
set -u
export DEBIAN_FRONTEND=noninteractive

echo "[wine] === Windows-apps (Box64 + Wine) setup on Debian ==="
. /etc/os-release 2>/dev/null || true
echo "[wine] Rootfs: ${PRETTY_NAME:-unknown}  arch: $(dpkg --print-architecture 2>/dev/null)"

mkdir -p /etc/apt/apt.conf.d
echo 'Acquire::Retries "5";' > /etc/apt/apt.conf.d/80-retries
apt-get update -y || apt-get update -y || echo "[wine] apt update had errors — continuing."

echo "[wine] Installing a light desktop + tools to host Wine windows…"
apt-get install -y --no-install-recommends \
    openbox tint2 xterm pcmanfm \
    dbus-x11 tigervnc-standalone-server tigervnc-common \
    fonts-dejavu-core procps \
    wget gnupg ca-certificates xz-utils cabextract \
    || echo "[wine] some desktop packages failed."

# --- Box64: x86-64 -> ARM64 translation -----------------------------------
echo "[wine] Installing Box64…"
wget -qO- https://ryanfortner.github.io/box64-debs/KEY.gpg \
    | gpg --dearmor -o /usr/share/keyrings/box64-debs-archive-keyring.gpg 2>/dev/null \
    || echo "[wine] box64 key fetch failed."
echo "deb [signed-by=/usr/share/keyrings/box64-debs-archive-keyring.gpg] https://ryanfortner.github.io/box64-debs/ ./" \
    > /etc/apt/sources.list.d/box64.list
apt-get update -y || true
# Try the common variants; one of them should match.
apt-get install -y box64-generic-arm \
    || apt-get install -y box64-android \
    || apt-get install -y box64 \
    || echo "[wine] box64 apt install failed."

echo "[wine] --- Box64 check ---"
if command -v box64 >/dev/null 2>&1; then
    box64 --version 2>&1 | head -3 || echo "[wine] box64 present but did not run."
else
    echo "[wine] box64 NOT installed."
fi

# --- Wine (x86-64 build, executed via Box64) ------------------------------
WINE_VER="9.0"
WINE_URL="https://github.com/Kron4ek/Wine-Builds/releases/download/${WINE_VER}/wine-${WINE_VER}-amd64.tar.xz"
echo "[wine] Downloading Wine ${WINE_VER} (x86-64)…"
mkdir -p /opt/wine
if wget -O /tmp/wine.tar.xz "$WINE_URL"; then
    tar -xf /tmp/wine.tar.xz -C /opt/wine --strip-components=1 && echo "[wine] Wine extracted." \
        || echo "[wine] Wine extract failed."
    rm -f /tmp/wine.tar.xz
else
    echo "[wine] Wine download failed."
fi

# `wine` / `wine64` wrappers that run the x86-64 Wine through Box64.
cat > /usr/local/bin/wine <<'EOF'
#!/bin/sh
export WINEPREFIX="${WINEPREFIX:-/root/.wine}"
exec box64 /opt/wine/bin/wine64 "$@"
EOF
cat > /usr/local/bin/wine64 <<'EOF'
#!/bin/sh
export WINEPREFIX="${WINEPREFIX:-/root/.wine}"
exec box64 /opt/wine/bin/wine64 "$@"
EOF
chmod +x /usr/local/bin/wine /usr/local/bin/wine64

echo "[wine] --- Wine check ---"
if [ -x /opt/wine/bin/wine64 ] && command -v box64 >/dev/null 2>&1; then
    box64 /opt/wine/bin/wine64 --version 2>&1 | head -3 || echo "[wine] wine did not run under box64 yet."
else
    echo "[wine] Wine or Box64 missing — see above."
fi

apt-get clean || true
rm -rf /var/lib/apt/lists/* || true
touch /root/.wt-desktop-ready
echo "[wine] === Setup attempt complete. Launch the desktop, open a terminal, run: wine notepad ==="
