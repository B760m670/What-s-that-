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

# Clean up any stale server/lock from a previous session.
vncserver -kill ":$DISPLAY_NUM" >/dev/null 2>&1 || true
rm -f "/tmp/.X${DISPLAY_NUM}-lock" "/tmp/.X11-unix/X${DISPLAY_NUM}" 2>/dev/null || true

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
