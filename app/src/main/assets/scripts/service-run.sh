#!/bin/bash
# service-run.sh — runs INSIDE the container (via run-in-ubuntu.sh).
#
# The always-on command — a relay, a server, a bot — is started through here.
# The app's supervisor restarts it whenever it exits; this script's job is
# everything that has to be true *inside* the container for "always on" to mean
# anything.
#
# The one thing it must do is register itself.
#
# Closing a desktop session sweeps every ptrace tracee under this app's uid,
# because that is the only criterion that identifies the guest without a list of
# process names that would have to grow with every desktop (see kill-session.sh).
# The relay is a tracee too. So without this registration, closing a desktop —
# or launching one, which sweeps first to clear the display — would take the
# relay down with it, which is the exact opposite of what it is for.
#
# proot traces every process of one container instance from a single tracer, so
# that tracer's pid names the whole relay at once: this shell, the command it
# execs, and anything that command forks later. Recording it lets the sweeps
# spare precisely the relay and stay exact about everything else.
set -u

STATE_DIR=/tmp/wt-service
LOG="$STATE_DIR/log"
mkdir -p "$STATE_DIR" 2>/dev/null || true

# Everything up to the redirect below goes to the app's pipe, which the
# supervisor copies into the same log file. Writing to both here would simply
# put every start-up line in twice.

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

# Field 22 of /proc/<pid>/stat: the process's start time in clock ticks since
# boot. Together with the pid it is a unique identity — pids are recycled, and a
# stale registration pointing at a recycled pid would spare the wrong process.
# The comm field (2) can contain spaces and parentheses, so it is cut off by its
# closing ")" rather than parsed.
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

WT_REQUIRED_FUNCTIONS="read_field read_starttime"
for _f in $WT_REQUIRED_FUNCTIONS; do
    if ! declare -F "$_f" >/dev/null; then
        echo "[service] INTERNAL ERROR: this script is missing $_f. Not starting."
        exit 1
    fi
done

CMD="${WT_SERVICE_CMD:-}"
if [ -z "$CMD" ]; then
    echo "[service] No command is configured — nothing to run."
    exit 78   # EX_CONFIG: the supervisor treats this as "do not hammer"
fi

read_field /proc/self/status "TracerPid:" || FIELD=0
TRACER="$FIELD"
read_starttime "$$" || START_TIME=""

printf '%s\n' "$TRACER"     > "$STATE_DIR/tracer"
printf '%s\n' "$$"          > "$STATE_DIR/pid"
printf '%s\n' "$START_TIME" > "$STATE_DIR/start"
printf '%s\n' "$CMD"        > "$STATE_DIR/command"

if [ "$TRACER" = 0 ]; then
    # Not traced: either this is not running under proot at all, or /proc is
    # hiding it. Either way the sweeps cannot be told to spare us, so say so
    # rather than letting the next desktop launch quietly kill the relay.
    echo "[service] WARNING: cannot see my own tracer, so a desktop session may end this process."
else
    echo "[service] registered as the always-on service (pid $$, proot $TRACER)"
fi

# Some server software expects these to exist; the desktop makes them, and the
# relay must not depend on a desktop ever having run.
export XDG_RUNTIME_DIR=/tmp/runtime-root
mkdir -p "$XDG_RUNTIME_DIR" 2>/dev/null || true
chmod 700 "$XDG_RUNTIME_DIR" 2>/dev/null || true

# Take as many file descriptors as this device will give.
#
# A node holding many connections wants far more than the default 1024, and the
# usual advice is `ulimit -n 65536`. That advice cannot be followed here, and
# this device has already said so in its own words:
#
#   dbus-daemon: Failed to set fd limit to 65536: Operation not permitted
#
# The hard limit belongs to Android, and fake root under proot does not raise
# it — proot fakes uid 0, not CAP_SYS_RESOURCE. So ask for the hard limit
# instead of a number from a document, and then say what was actually granted,
# because "I asked for 65536" and "I have 65536" are different facts and only
# the second one matters to a process running out of descriptors.
_hard=$(ulimit -Hn 2>/dev/null || echo "")
case "$_hard" in
    unlimited) ulimit -n 65536 2>/dev/null || true ;;
    ''|*[!0-9]*) : ;;
    *) ulimit -n "$_hard" 2>/dev/null || true ;;
esac
echo "[service] file descriptors: $(ulimit -n) soft, ${_hard:-?} hard."

# The command's output goes to a file, never to the app's pipe.
#
# That is not a convenience. If the app's process is killed — swept from
# recents, or reclaimed under memory pressure — the read end of that pipe dies
# with it, and the next thing the relay writes to stdout gets EPIPE and SIGPIPE.
# A relay that is killed by the death of the app watching it is not always-on,
# so it writes somewhere that does not depend on anyone watching. The app reads
# this file instead; /tmp here is a directory in app storage, so it can.
#
# Rotation happens here and only here, at a moment when nothing else holds the
# file: the supervisor starts this script only once the previous run has exited.
if [ -f "$LOG" ]; then
    SIZE=$(stat -c %s "$LOG" 2>/dev/null || echo 0)
    case "$SIZE" in
        ''|*[!0-9]*) SIZE=0 ;;
    esac
    if [ "$SIZE" -gt 1048576 ]; then
        mv -f "$LOG" "$LOG.1" 2>/dev/null || true
        echo "[service] previous log rotated to $(basename "$LOG").1"
    fi
fi

exec >> "$LOG" 2>&1

# exec, so the registered pid above stays the pid of the running command: the
# supervisor's handle and the sweeps' exemption both refer to the real thing
# rather than to a wrapper that is no longer the process doing the work.
exec bash -lc "$CMD"
