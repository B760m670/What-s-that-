#!/bin/sh
# kill-session.sh — stop any running VNC desktop session (runs in the container).
# Scans the shared /proc (no procps needed) and kills the VNC server, then clears
# the shared X locks so the next launch starts clean.
DISPLAY_NUM=1
for p in /proc/[0-9]*; do
    if grep -qaE 'Xtigervnc|/Xvnc' "$p/cmdline" 2>/dev/null; then
        kill "${p#/proc/}" 2>/dev/null || true
    fi
done
rm -f "/tmp/.X${DISPLAY_NUM}-lock" "/tmp/.X11-unix/X${DISPLAY_NUM}" 2>/dev/null || true
echo "[session] closed"
