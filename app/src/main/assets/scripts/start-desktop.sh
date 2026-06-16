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

echo "[desktop] Starting XFCE on :$DISPLAY_NUM (${GEOMETRY})..."
# -localhost: only the on-device app can reach it. SecurityTypes None because
# the socket never leaves localhost inside the app sandbox.
vncserver ":$DISPLAY_NUM" \
    -geometry "$GEOMETRY" \
    -depth "$DEPTH" \
    -localhost yes \
    -SecurityTypes None

echo "[desktop] XFCE is running."
echo "VNC_READY 127.0.0.1:$PORT"
