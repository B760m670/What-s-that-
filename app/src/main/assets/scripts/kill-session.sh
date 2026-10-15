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
#
# The always-on service is the one deliberate exception. It is a tracee like
# everything else, so the criterion above would end it too — and a relay that
# dies whenever someone closes a desktop is not a relay. service-run.sh records
# the proot that traces it, and the sweep spares that tracer's tracees: one
# container instance, named exactly, rather than a name pattern that would also
# spare a desktop process that happened to match.
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

# Field 22 of /proc/<pid>/stat — the process's start time. Pids are recycled, so
# "pid 9312 is still there" is not the same as "pid 9312 is still the relay";
# the start time settles it. The comm field can contain spaces and parentheses,
# hence cutting at the closing ")" instead of parsing.
START_TIME=""
read_starttime() {   # $1 = pid
    START_TIME=""
    local line rest
    [ -r "/proc/$1/stat" ] || return 1
    read -r line < "/proc/$1/stat" || return 1
    rest=${line#*") "}
    # shellcheck disable=SC2086  # deliberate word splitting into positionals
    set -- $rest
    START_TIME="${20:-}"
    [ -n "$START_TIME" ]
}

# The one container instance the sweep must not touch: the always-on service.
# Everything it recorded is verified before it is trusted — a leftover file from
# a relay that died must not spare an unrelated process that inherited its pid,
# which would leave a desktop running that we just told the user was closed.
SPARE_TRACER=0
load_spare_tracer() {
    SPARE_TRACER=0
    local dir=/tmp/wt-service tracer pid start
    [ -r "$dir/tracer" ] && [ -r "$dir/pid" ] || return 0
    read -r tracer < "$dir/tracer" || return 0
    read -r pid < "$dir/pid" || return 0
    [ -n "$tracer" ] && [ "$tracer" != 0 ] && [ -n "$pid" ] || return 0
    read_field "/proc/$pid/status" "TracerPid:" 2>/dev/null || return 0
    [ "$FIELD" = "$tracer" ] || return 0
    if [ -r "$dir/start" ] && read -r start < "$dir/start" && [ -n "$start" ]; then
        read_starttime "$pid" || return 0
        [ "$START_TIME" = "$start" ] || return 0
    fi
    SPARE_TRACER="$tracer"
    echo "[session] leaving the always-on service running (proot $tracer)."
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
        [ "$SPARE_TRACER" != 0 ] && [ "$FIELD" = "$SPARE_TRACER" ] && continue
        kill -"$1" "$pid" 2>/dev/null
    done
}

# Every function below this line is one the script cannot run without. The list
# is checked at runtime, and tools/check-scripts.py checks it against what the
# file actually defines — because an edit once removed four of these at once,
# the file still parsed, CI stayed green, and the phone said
# "wait_for_session: command not found" halfway through a launch.
WT_REQUIRED_FUNCTIONS="read_field read_starttime load_spare_tracer sweep"
for _f in $WT_REQUIRED_FUNCTIONS; do
    if ! declare -F "$_f" >/dev/null; then
        echo "[desktop] INTERNAL ERROR: this script is missing $_f. Not launching."
        exit 1
    fi
done

# 0. Find out what must survive this, before anything is signalled.
load_spare_tracer

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
