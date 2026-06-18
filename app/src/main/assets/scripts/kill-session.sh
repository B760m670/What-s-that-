#!/bin/sh
# kill-session.sh — stop the running VNC desktop session (runs in the container).
#
# proot shares the host PID namespace, so the session's processes are visible
# and signal-able via /proc (no procps needed). We try TigerVNC's own clean
# kill first, then escalate (TERM, then KILL) over anything VNC/desktop-ish —
# the old version only sent a single TERM to Xvnc, which sometimes left the
# server alive — and finally clear this distro's X locks.
DISPLAY_NUM=1
export HOME=/root

# 1) Clean shutdown via TigerVNC's pidfile, if present.
vncserver -kill ":$DISPLAY_NUM" >/dev/null 2>&1 || true

# 2) Force-kill any lingering server/session processes.
signalled=0
for sig in TERM KILL; do
    for p in /proc/[0-9]*; do
        cmd=$(tr '\0' ' ' < "$p/cmdline" 2>/dev/null) || continue
        case "$cmd" in
            *Xtigervnc*|*Xvnc*|*vncserver*|*xfce4-session*|*xfwm4*|*openbox*|*startxfce4*)
                if kill -"$sig" "${p#/proc/}" 2>/dev/null; then
                    signalled=$((signalled + 1))
                fi
                ;;
        esac
    done
    sleep 1
done

# 3) Clear stale X locks for this display so the next launch starts clean.
rm -f "/tmp/.X${DISPLAY_NUM}-lock" "/tmp/.X11-unix/X${DISPLAY_NUM}" 2>/dev/null || true
echo "[session] closed (signalled $signalled process(es))"
