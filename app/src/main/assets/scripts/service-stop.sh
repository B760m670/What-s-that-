#!/bin/bash
# service-stop.sh — runs INSIDE the container (via run-in-ubuntu.sh).
#
# Stops the always-on service, and only it.
#
# Destroying the app's process handle is not enough and not right. That handle
# is the proot that traces the relay, and proot is the tracer, not the work:
# killing a tracer leaves its tracees stopped and orphaned, holding whatever
# they held. So the relay is stopped the same way the desktop is — by signalling
# the tracees and letting the tracer retire on its own — except that here the
# criterion is inverted: everything traced by the *service's* proot, and nothing
# else. A relay being stopped must not take a desktop session with it.
set -u

STATE_DIR=/tmp/wt-service

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

# Signal every process traced by $1, except ourselves and our ancestors — this
# script runs in a container of its own, so its own tracer is a different proot,
# but being careful costs nothing and a mistake here is unrecoverable.
sweep_tracees() {   # $1 = tracer pid  $2 = signal
    local d pid killed=0
    for d in /proc/[0-9]*; do
        pid=${d#/proc/}
        case "$PROTECT" in *" $pid "*) continue ;; esac
        [ -r "$d/status" ] || continue
        read_field "$d/status" "TracerPid:" 2>/dev/null || continue
        if [ "$FIELD" = "$1" ]; then
            kill -"$2" "$pid" 2>/dev/null
            killed=$((killed + 1))
        fi
    done
    echo "$killed"
}

WT_REQUIRED_FUNCTIONS="read_field sweep_tracees"
for _f in $WT_REQUIRED_FUNCTIONS; do
    if ! declare -F "$_f" >/dev/null; then
        echo "[service] INTERNAL ERROR: this script is missing $_f. Not stopping anything."
        exit 1
    fi
done

PROTECT=" $$ "
_p=$$
while [ -n "$_p" ] && [ "$_p" != 0 ] && [ "$_p" != 1 ]; do
    read_field "/proc/$_p/status" "PPid:" 2>/dev/null || break
    _p=$FIELD
    PROTECT="$PROTECT$_p "
done

TRACER=0
if [ -r "$STATE_DIR/tracer" ]; then
    read -r TRACER < "$STATE_DIR/tracer" || TRACER=0
fi
case "$TRACER" in
    ''|*[!0-9]*) TRACER=0 ;;
esac

if [ "$TRACER" = 0 ]; then
    echo "[service] nothing registered — nothing to stop."
else
    n=$(sweep_tracees "$TRACER" TERM)
    echo "[service] asked $n process(es) to stop."
    sleep 2
    sweep_tracees "$TRACER" KILL >/dev/null
fi

# Clear the registration last. While it exists the desktop's sweeps spare this
# tracer, and a stale file could spare a process that inherited its pid.
rm -f "$STATE_DIR/tracer" "$STATE_DIR/pid" "$STATE_DIR/start" "$STATE_DIR/command" 2>/dev/null || true
echo "[service] stopped"
