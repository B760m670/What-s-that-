#!/bin/sh
# kill-session.sh — stop the running VNC desktop session (runs in the container).
#
# proot shares the host PID namespace, so the session's processes are visible
# and signal-able via /proc (no procps needed). Try TigerVNC's own clean kill
# first, then escalate TERM -> KILL over anything VNC/desktop-ish, and finally
# clear the X locks so the next launch starts clean.
DISPLAY_NUM=1
export HOME=/root

vncserver -kill ":$DISPLAY_NUM" >/dev/null 2>&1 || true

# Read cmdline with $(<file) (bash reads it without forking) and match with a
# case builtin, so reaping a heavy session does not itself need to spawn a `tr`
# per process — which can fail once the process budget is exhausted.
for sig in TERM KILL; do
    for p in /proc/[0-9]*; do
        [ -r "$p/cmdline" ] || continue
        { cmd=$(<"$p/cmdline"); } 2>/dev/null   # group redirect hides the NUL-byte warning
        case "$cmd" in
            *Xtigervnc*|*Xvnc*|*Xvfb*|*vncserver*|*xfce4-session*|*xfwm4*)
                kill -"$sig" "${p#/proc/}" 2>/dev/null || true ;;
        esac
    done
done

rm -f "/tmp/.X${DISPLAY_NUM}-lock" "/tmp/.X11-unix/X${DISPLAY_NUM}" 2>/dev/null || true
echo "[session] closed"
