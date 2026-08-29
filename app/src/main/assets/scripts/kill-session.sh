#!/bin/bash
# kill-session.sh — stop the running desktop session (runs in the container).
#
# The old version matched a hardcoded list of process names
# (Xvnc|xfce4-session|xfwm4|…). Such a list is wrong by construction: it has to
# be extended for every desktop we ever add, and the day it is not, the session
# survives being "closed" — which is exactly what would happen to GNOME, whose
# processes are gnome-shell, gnome-session-binary and a dozen gsd-* daemons.
#
# There is an exact criterion available instead. proot works by ptrace, so every
# process inside the container — whatever it is called, whichever distro it came
# from — is a tracee, and nothing else running under this app's uid is traced.
# `TracerPid` in /proc/<pid>/status therefore identifies the guest precisely, and
# keeps doing so for desktops that do not exist yet.
#
# The sweep itself forks nothing — no $(...), no pipes, no external binaries.
# A heavy session that has exhausted the process budget is exactly when this
# script is needed, and that is when `fork: Function not implemented` would
# otherwise take out the cleanup. (The `vncserver -kill` and `sleep` around it
# do fork; if they fail the sweep still runs, just with less grace.)
#
# One consequence worth knowing: a console command running in the container at
# the same time is a tracee too, so closing the session ends it as well.
DISPLAY_NUM=1
export HOME=/root

# Read one "Prefix:" field out of a /proc file into $FIELD, without forking.
FIELD=""
read_field() {   # $1=file  $2=field prefix, e.g. "TracerPid:"
    FIELD=""
    local line
    while IFS= read -r line; do
        case $line in
            "$2"*)
                line=${line#"$2"}
                FIELD=${line//[[:space:]]/}
                return 0 ;;
        esac
    done < "$1"
    return 1
}

# Our own process and its ancestors are tracees too — sweeping blindly would
# kill the shell doing the sweeping, half way through.
PROTECT=" $$ "
_p=$$
while [ -n "$_p" ] && [ "$_p" != 0 ] && [ "$_p" != 1 ]; do
    read_field "/proc/$_p/status" "PPid:" 2>/dev/null || break
    _p=$FIELD
    PROTECT="$PROTECT$_p "
done

# Signal every guest process except ourselves. Deliberately leaves the proot
# processes alone (they are the tracers, not tracees): they exit on their own
# once the last tracee is gone, and killing a tracer early would orphan
# everything it was still holding.
sweep() {   # $1 = signal
    local d pid
    for d in /proc/[0-9]*; do
        pid=${d#/proc/}
        case "$PROTECT" in *" $pid "*) continue ;; esac
        [ -r "$d/status" ] || continue
        read_field "$d/status" "TracerPid:" 2>/dev/null || continue
        [ "$FIELD" = 0 ] && continue
        kill -"$1" "$pid" 2>/dev/null
    done
}

# 1. Let TigerVNC take the session down its own way first: that runs the
#    server's shutdown path and gives the desktop a chance to save state.
vncserver -kill ":$DISPLAY_NUM" >/dev/null 2>&1 || true

# 2. Ask, then insist. GNOME in particular wants a moment to write dconf.
sweep TERM
sleep 2
sweep KILL

# 3. The X locks live in the /tmp we share across distros, so a leftover here
#    blocks the NEXT launch — including a launch of a different distro.
rm -f "/tmp/.X${DISPLAY_NUM}-lock" "/tmp/.X11-unix/X${DISPLAY_NUM}" 2>/dev/null || true
rm -f "/root/.vnc/"*":${DISPLAY_NUM}.pid" 2>/dev/null || true
echo "[session] closed"
