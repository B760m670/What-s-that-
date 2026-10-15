#!/bin/bash
# Exercises the relay exemption in the two scripts that sweep the container.
#
# The real logic is used, not a copy of it: each script's own text is read, and
# its two paths — /proc and the registration directory — are redirected into a
# fixture. If either sweep's criterion is edited, this notices.
#
# Why it is worth testing at all: the sweep is how a session is closed, the
# relay is a ptrace tracee exactly like a desktop process, and the difference
# between "closing the desktop" and "killing the user's relay" is these few
# lines. There is no way to try it on a phone except by losing the relay.
set -u
cd "$(dirname "$0")"

SCRIPTS=../app/src/main/assets/scripts
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

PROC="$WORK/proc"
STATE="$WORK/state"
LIB="$WORK/lib.sh"

# /proc/<pid>/stat as the kernel lays it out: field 1 is the pid, field 2 the
# parenthesised comm, field 3 the state, field 22 the start time. The comm here
# deliberately contains a space, because that is what makes naive field
# splitting of this file wrong.
FILLER=""   # fields 4..21, between the state and the start time
for _i in $(seq 1 18); do FILLER="$FILLER 1"; done

mkproc() {   # pid tracerpid starttime [cmdline]
    mkdir -p "$PROC/$1"
    printf 'Name:\tthing\nPPid:\t1\nTracerPid:\t%s\n' "$2" > "$PROC/$1/status"
    printf '%s (a thing) S%s %s 0\n' "$1" "$FILLER" "$3" > "$PROC/$1/stat"
    printf '%s' "${4:-/usr/bin/thing}" > "$PROC/$1/cmdline"
}

register() {   # tracer pid start
    printf '%s\n' "$1" > "$STATE/tracer"
    printf '%s\n' "$2" > "$STATE/pid"
    printf '%s\n' "$3" > "$STATE/start"
}

reset() {   # $1 = source script, $2 = sed address of the first action in it
    rm -rf "$PROC" "$STATE"
    mkdir -p "$PROC" "$STATE"
    sed "$2,\$d" "$SCRIPTS/$1" \
        | sed -e "s#/proc#$PROC#g" -e "s#/tmp/wt-service#$STATE#g" > "$LIB"
}

fail=0
check() {   # label expected actual
    if [ "$2" = "$3" ]; then
        echo "ok   $1"
    else
        echo "FAIL $1: expected '$2', got '$3'"
        fail=1
    fi
}

spare() {
    # shellcheck disable=SC1090
    ( . "$LIB" >/dev/null 2>&1; load_spare_tracer >/dev/null 2>&1; echo "$SPARE_TRACER" )
}

# Run one of the sweeps with kill replaced by a recorder, so the test observes
# the decision instead of the consequences.
# The recorder reports on fd 3, not stdout: the sweeps print summaries of their
# own, and mixing the two would make the test read its own narration.
swept() {   # $@ = the sweep to call, with its arguments
    # shellcheck disable=SC1090
    (
        . "$LIB" >/dev/null 2>&1
        kill() { shift; printf '%s ' "$1" >&3; }
        # Not every script here has a loader (service-stop.sh inverts the
        # criterion instead of excluding it), so this is best-effort.
        load_spare_tracer >/dev/null 2>&1 || true
        "$@" >/dev/null 2>&1
    ) 3>&1
}

# --- the registration, judged -------------------------------------------------
#
# Both scripts carry the same loader; both are checked, because the day they
# drift is the day one of them starts killing the relay.
for pair in "kill-session.sh|/^# 0\. Find out/" "start-desktop.sh|/^load_spare_tracer\$/"; do
    src=${pair%%|*}
    cut=${pair##*|}
    echo "--- $src"

    reset "$src" "$cut"
    mkproc 900 555 12345
    register 555 900 12345
    check "$src: a live registration is spared" 555 "$(spare)"

    rm -rf "$PROC/900"
    check "$src: a dead registration is not spared" 0 "$(spare)"

    mkproc 900 777 99999
    check "$src: a recycled pid under another tracer is not spared" 0 "$(spare)"

    # Same tracer, so TracerPid matches, but not the process that registered.
    mkproc 900 555 99999
    check "$src: a recycled pid with a different start time is not spared" 0 "$(spare)"

    mkproc 900 555 12345
    rm -f "$STATE/tracer" "$STATE/pid" "$STATE/start"
    check "$src: no registration means nothing is spared" 0 "$(spare)"

    register 0 900 12345
    check "$src: a registration with no tracer is not spared" 0 "$(spare)"

    # --- the sweep itself -----------------------------------------------------
    #
    # Four processes: two of the desktop's proot, one of the relay's, and one
    # host-side process that is not traced at all. Only the desktop's two may
    # be signalled.
    reset "$src" "$cut"
    mkproc 100 42 1000
    mkproc 101 42 1001
    mkproc 900 555 12345
    mkproc 700 0 1002
    register 555 900 12345
    check "$src: the sweep signals the desktop and spares the relay" \
        "100 101 " "$(swept sweep TERM)"

    # And with no relay registered, nothing changes about the sweep's job.
    rm -f "$STATE/tracer" "$STATE/pid" "$STATE/start"
    check "$src: with no relay, the sweep still clears the guest" \
        "100 101 900 " "$(swept sweep TERM)"
done

# --- the name-based orphan sweep ----------------------------------------------
#
# start-desktop.sh also clears leftovers by name, for processes whose proot has
# exited and which therefore have no tracer to identify them. A relay that
# happens to run something on that list must still survive it.
echo "--- start-desktop.sh (orphans)"
reset start-desktop.sh "/^load_spare_tracer\$/"
mkproc 100 0 1000 "/usr/bin/gnome-shell"              # a real orphan
mkproc 900 555 12345 "/usr/bin/dbus-daemon --session"  # the relay's own bus
register 555 900 12345
check "start-desktop.sh: orphans are cleared but the relay's lookalike is not" \
    "100 " "$(swept sweep_orphans)"

# --- stopping the relay -------------------------------------------------------
#
# The same criterion, inverted: service-stop.sh signals the relay's tracees and
# must leave a desktop session running. Switching the relay off is not a reason
# to close someone's windows.
echo "--- service-stop.sh"
reset service-stop.sh "/^TRACER=0\$/"
mkproc 100 42 1000      # a desktop process
mkproc 900 555 12345    # the relay
mkproc 901 555 12346    # something the relay started
mkproc 700 0 1002       # not traced at all
register 555 900 12345
check "service-stop.sh: only the relay's own tracees are signalled" \
    "900 901 " "$(swept sweep_tracees 555 TERM)"

exit $fail
