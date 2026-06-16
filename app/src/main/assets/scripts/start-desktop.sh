#!/bin/bash
# start-desktop.sh — runs INSIDE the Ubuntu container (via run-in-ubuntu.sh).
#
# Starts (or restarts) the XFCE session on a VNC display, then prints the
# loopback address the app's built-in VNC viewer connects to.
set -eu

DISPLAY_NUM=1
GEOMETRY="${WT_GEOMETRY:-1280x720}"
DEPTH=24
PORT=$((5900 + DISPLAY_NUM))

# Clean up any server/lock from a previous session — including one started by a
# DIFFERENT distro. proot shares the host PID namespace and we bind one /tmp for
# all distros, so a VNC server from another rootfs still holds display :1, and
# its pid-file lives in another rootfs's ~/.vnc where `vncserver -kill` can't see
# it. Scan the shared /proc (no procps needed) and kill the server directly, then
# clear the shared X locks.
vncserver -kill ":$DISPLAY_NUM" >/dev/null 2>&1 || true
for p in /proc/[0-9]*; do
    if grep -qaE 'Xtigervnc|/Xvnc' "$p/cmdline" 2>/dev/null; then
        kill "${p#/proc/}" 2>/dev/null || true
    fi
done
rm -f "/tmp/.X${DISPLAY_NUM}-lock" "/tmp/.X11-unix/X${DISPLAY_NUM}" 2>/dev/null || true
rm -f "/root/.vnc/"*":${DISPLAY_NUM}.pid" 2>/dev/null || true
sleep 1

# Clean stale session/auth state — a relaunch (esp. after switching distros)
# inherits a broken ICE/X authority and fails xfce4-session.
export HOME=/root
export XDG_RUNTIME_DIR=/tmp/runtime-root
mkdir -p "$XDG_RUNTIME_DIR" && chmod 700 "$XDG_RUNTIME_DIR" 2>/dev/null || true
rm -f /root/.ICEauthority /root/.Xauthority 2>/dev/null || true

# Regenerate xstartup every launch (so fixes apply without reinstalling the
# desktop). Pick whatever session is installed.
if command -v startxfce4 >/dev/null 2>&1; then SESSION_CMD="startxfce4"
elif command -v openbox-session >/dev/null 2>&1; then SESSION_CMD="openbox-session"
elif command -v startlxqt >/dev/null 2>&1; then SESSION_CMD="startlxqt"
else SESSION_CMD="xterm"; fi

# Alpine/musl: proot's faccessat gap breaks GTK's *search* for its pixbuf
# loaders and icon caches. Build those caches and point GTK straight at the
# loader cache via GDK_PIXBUF_MODULE_FILE so it doesn't have to search.
PIXBUF_ENV=""
if [ -f /etc/alpine-release ]; then
    CACHE=$(ls /usr/lib/gdk-pixbuf-2.0/*/loaders.cache 2>/dev/null | head -1)
    [ -n "$CACHE" ] || CACHE=/usr/lib/gdk-pixbuf-2.0/2.10.0/loaders.cache
    mkdir -p "$(dirname "$CACHE")"
    gdk-pixbuf-query-loaders > "$CACHE" 2>/dev/null || true
    gtk-update-icon-cache -f -t /usr/share/icons/hicolor 2>/dev/null || true
    gtk-update-icon-cache -f -t /usr/share/icons/Adwaita 2>/dev/null || true
    update-mime-database /usr/share/mime 2>/dev/null || true
    PIXBUF_ENV="export GDK_PIXBUF_MODULE_FILE='$CACHE'"
fi

mkdir -p /root/.vnc
cat > /root/.vnc/xstartup <<EOF
#!/bin/sh
unset SESSION_MANAGER
unset DBUS_SESSION_BUS_ADDRESS
export HOME=/root
export XDG_RUNTIME_DIR=/tmp/runtime-root
export XKL_XMODMAP_DISABLE=1
$PIXBUF_ENV
exec dbus-launch --exit-with-session $SESSION_CMD
EOF
chmod +x /root/.vnc/xstartup

echo "[desktop] Starting XFCE on :$DISPLAY_NUM (${GEOMETRY})..."
# Two TigerVNC generations: older (Ubuntu/Debian) takes options on the command
# line; newer (Alpine) takes only the display and reads ~/.vnc/config. Try the
# legacy form first (proven), then fall back to the config-based form.
if vncserver ":$DISPLAY_NUM" \
        -geometry "$GEOMETRY" \
        -depth "$DEPTH" \
        -localhost yes \
        -SecurityTypes None; then
    :
else
    echo "[desktop] Retrying with config-based vncserver (newer TigerVNC)…"
    rm -f "/tmp/.X${DISPLAY_NUM}-lock" "/tmp/.X11-unix/X${DISPLAY_NUM}" 2>/dev/null || true
    mkdir -p /root/.vnc
    cat > /root/.vnc/config <<EOF
geometry=$GEOMETRY
depth=$DEPTH
SecurityTypes=None
rfbport=$PORT
interface=127.0.0.1
EOF
    vncserver ":$DISPLAY_NUM"
fi

echo "[desktop] XFCE is running."
echo "VNC_READY 127.0.0.1:$PORT"
