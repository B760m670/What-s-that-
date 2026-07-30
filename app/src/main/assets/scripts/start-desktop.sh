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

# Clean stale session/auth state — a relaunch (esp. after switching distros)
# inherits a broken ICE/X authority and fails xfce4-session.
export HOME=/root
export XDG_RUNTIME_DIR=/tmp/runtime-root
mkdir -p "$XDG_RUNTIME_DIR" && chmod 700 "$XDG_RUNTIME_DIR" 2>/dev/null || true
rm -f /root/.ICEauthority /root/.Xauthority 2>/dev/null || true

# Regenerate xstartup every launch (so fixes apply without reinstalling the
# desktop). Honour the desktop chosen in the app, but only if it is actually
# present — a rootfs can hold several, and the user may have switched to one
# that has not been installed here yet. Falling back to whatever IS installed
# beats starting nothing at all.
SESSION_CMD=""
if [ -n "${WT_DE_SESSION:-}" ] && command -v "$WT_DE_SESSION" >/dev/null 2>&1; then
    SESSION_CMD="$WT_DE_SESSION"
else
    [ -n "${WT_DE_SESSION:-}" ] && \
        echo "[desktop] $WT_DE_SESSION is not installed here — using another desktop."
    for s in startxfce4 mate-session startlxqt startlxde openbox-session startplasma-x11 gnome-session; do
        if command -v "$s" >/dev/null 2>&1; then SESSION_CMD="$s"; break; fi
    done
fi
[ -n "$SESSION_CMD" ] || SESSION_CMD="xterm"
echo "[desktop] session: $SESSION_CMD"

# GNOME must be told this is an X11 session. Left to itself it looks for
# Wayland, finds none, and gives up — on a VNC/Xvfb display there is only ever
# an X server. The variable is harmless for the other desktops.
SESSION_LAUNCH="$SESSION_CMD"
if [ "$SESSION_CMD" = "gnome-session" ]; then
    SESSION_LAUNCH="gnome-session --session=gnome-xorg"
    export XDG_SESSION_TYPE=x11
    export GDK_BACKEND=x11
fi

# Openbox on its own shows only a bare background + cursor, and touch VNC has no
# right-click to reach its menu. Autostart a panel and a terminal so there's an
# immediately usable desktop (e.g. to run `wine notepad`).
if [ "$SESSION_CMD" = "openbox-session" ]; then
    mkdir -p /root/.config/openbox
    cat > /root/.config/openbox/autostart <<'EOF'
command -v tint2 >/dev/null 2>&1 && tint2 &
command -v xterm >/dev/null 2>&1 && xterm -geometry 110x30+30+30 &
EOF
fi

# Audio: the container can't reach the speaker directly. Run PulseAudio here,
# route everything to a null sink, and stream that sink's PCM over loopback TCP
# (s16le/48k/stereo) — the app's AudioBridge reads 127.0.0.1:4712 and plays it.
#
# This used to hide every step behind >/dev/null || true, so a silent desktop
# gave no clue why. Now each step reports, the way the Xvfb and GPU paths do.
if command -v pulseaudio >/dev/null 2>&1; then
    pulseaudio --kill >/dev/null 2>&1 || true
    sleep 1

    # PulseAudio refuses to run as root unless told to allow it, and under proot
    # we are fake-root — the likeliest reason there has never been sound. Allow
    # it explicitly via a daemon config, then start.
    mkdir -p /root/.config/pulse
    cat > /root/.config/pulse/daemon.conf <<'PA'
allow-exit = no
exit-idle-time = -1
PA
    if pulseaudio --start --exit-idle-time=-1 --disable-shm=true 2>/tmp/.wt-pa.log; then
        echo "[audio] PulseAudio started"
    else
        echo "[audio] PulseAudio failed to start; it said:"
        sed 's/^/[audio]   /' /tmp/.wt-pa.log 2>/dev/null | head -4
    fi
    sleep 1

    if pactl info >/dev/null 2>&1; then
        pactl load-module module-null-sink sink_name=wt \
            sink_properties=device.description=WhatsThat >/dev/null 2>&1 \
            && echo "[audio] null sink 'wt' loaded" \
            || echo "[audio] could not load null sink"
        pactl set-default-sink wt >/dev/null 2>&1 || true
        # Route anything already playing to our sink too, not just new streams.
        for i in $(pactl list short sink-inputs 2>/dev/null | cut -f1); do
            pactl move-sink-input "$i" wt >/dev/null 2>&1 || true
        done
        if pactl load-module module-simple-protocol-tcp record=true source=wt.monitor \
            listen=127.0.0.1 port=4712 format=s16le rate=48000 channels=2 >/dev/null 2>&1; then
            echo "[audio] streaming wt.monitor on 127.0.0.1:4712 — app should have sound"
        else
            echo "[audio] could not open the TCP audio stream on 4712"
        fi
    else
        echo "[audio] PulseAudio is not answering; no sound this session"
    fi
fi

# "Desktop is running" only ever meant the X server came up. The session inside
# it can still die — GNOME's "Oh no! Something has gone wrong" is exactly that,
# and its reason goes to the session log, which the app never showed. So report
# it: wait for the session to settle, then echo anything that looks fatal.
#
# Backgrounded, because the launcher must return promptly with the READY line;
# the app keeps draining our output afterwards, so late lines still arrive.
session_health_report() {  # $1=log dir  $2=log filename suffix
    local dir="$1" suffix="$2"
    (
        sleep 12
        log=$(ls -t "$dir"/*"$suffix" 2>/dev/null | head -1)
        [ -n "$log" ] || exit 0
        hits=$(grep -aiE 'Oh no|has gone wrong|failed to (start|initialize|create|register)|could not|cannot open|no such file|segmentation|core dumped|not authorized|Fatal|GLX|EGL_|libGL error|assertion' \
            "$log" 2>/dev/null | tail -10)
        [ -n "$hits" ] || exit 0
        echo "[session] the desktop session reported problems:"
        printf '%s\n' "$hits" | cut -c1-170 | sed 's/^/[session]   /'
    ) &
}

mkdir -p /root/.vnc
cat > /root/.vnc/xstartup <<EOF
#!/bin/sh
unset SESSION_MANAGER
unset DBUS_SESSION_BUS_ADDRESS
export HOME=/root
export XDG_RUNTIME_DIR=/tmp/runtime-root
export XKL_XMODMAP_DISABLE=1
export XDG_SESSION_TYPE=x11
exec dbus-launch --exit-with-session $SESSION_LAUNCH
EOF
chmod +x /root/.vnc/xstartup

# --- GPU probe ---------------------------------------------------------------
#
# A live socket is NOT proof that virgl works. If the container's Mesa and the
# host-side server disagree on the vtest protocol version, the connection is
# accepted and then the client *aborts* ("lost connection to rendering server",
# SIGABRT) — Mesa does not fall back to software on its own. Committing the
# session to virpipe on faith would therefore crash every GL app on the desktop
# instead of merely making it slow.
#
# So prove it first, on a throwaway display so the real session never sees a
# half-broken GL stack, and fall back deliberately if the probe does not come
# back with a renderer.
#
# The probe needs glxinfo, which only arrives with mesa-utils. Containers whose
# desktop was installed before GPU support existed do not have it — and treating
# "no way to check" as "GL is broken" would disable virgl on every such install
# for want of a diagnostic tool. So top the packages up once, here, instead of
# only in install-desktop.sh, which a Launch (as opposed to Install) never runs.
gpu_ensure_packages() {
    # glxgears is the probe now, glxinfo only the fallback; both come from
    # mesa-utils, so gate on the one we actually need first.
    command -v glxgears >/dev/null 2>&1 && return 0
    echo "[gpu] GL packages missing in this container — installing them once..."
    export DEBIAN_FRONTEND=noninteractive
    (apt-get update -y && apt-get install -y --no-install-recommends \
        libgl1-mesa-dri libglx-mesa0 mesa-utils) >/dev/null 2>&1 || true
    command -v glxgears >/dev/null 2>&1
}

PROBE_DISPLAY=99
gpu_probe_renderer() {
    rm -f "/tmp/.X${PROBE_DISPLAY}-lock" "/tmp/.X11-unix/X${PROBE_DISPLAY}" 2>/dev/null || true
    Xvnc ":$PROBE_DISPLAY" -geometry 320x240 -depth 24 \
        -localhost yes -SecurityTypes None >/dev/null 2>&1 &
    probe_pid=$!
    i=0
    while [ "$i" -lt 20 ]; do
        [ -e "/tmp/.X11-unix/X${PROBE_DISPLAY}" ] && break
        i=$((i + 1)); sleep 0.5
    done
    # Ask glxgears, not glxinfo. Under virgl the drawable is presented by
    # reading it back with XGetImage, and XGetImage on a window that was never
    # mapped fails with BadMatch. glxinfo never maps its window, so it reports
    # BadMatch whether or not virgl works — which is exactly what the first
    # device run hit, and it told us nothing. glxgears calls XMapWindow before
    # drawing, so the drawable is viewable and the failure, if any, is real.
    #
    # glxgears never exits on its own, so waiting for it is waiting for the
    # timeout — 12 seconds added to EVERY desktop launch, for a line it prints
    # in well under one. Run it to a file and stop the moment the line lands
    # (or it dies); the timeout becomes the worst case, not the normal one.
    # stdout is forced line-buffered so the line reaches the file promptly and
    # is not lost in the pipe buffer when the process is killed.
    gears_out="/tmp/.wt-gpu-gears.$$"
    : > "$gears_out"
    if command -v stdbuf >/dev/null 2>&1; then
        DISPLAY=":$PROBE_DISPLAY" stdbuf -oL -eL glxgears -info > "$gears_out" 2>&1 &
    else
        DISPLAY=":$PROBE_DISPLAY" glxgears -info > "$gears_out" 2>&1 &
    fi
    gears_pid=$!
    i=0
    while [ "$i" -lt 24 ]; do
        grep -q '^GL_RENDERER' "$gears_out" 2>/dev/null && break
        kill -0 "$gears_pid" 2>/dev/null || break   # it exited; whatever it said is final
        i=$((i + 1)); sleep 0.5
    done
    kill "$gears_pid" 2>/dev/null || true
    wait "$gears_pid" 2>/dev/null || true
    probe_out="$(cat "$gears_out" 2>/dev/null || true)"
    rm -f "$gears_out"
    renderer="$(printf '%s' "$probe_out" | sed -n 's/^GL_RENDERER *= *//p' | head -1)"

    # Fall back to glxinfo only if glxgears told us nothing at all — on a stack
    # where the mapping issue does not bite, it still answers.
    if [ -z "$renderer" ]; then
        probe_alt="$(DISPLAY=":$PROBE_DISPLAY" timeout 15 glxinfo -B 2>&1 || true)"
        renderer="$(printf '%s' "$probe_alt" | sed -n 's/^OpenGL renderer string: *//p' | head -1)"
        probe_out="$probe_out
--- glxinfo ---
$probe_alt"
    fi

    kill "$probe_pid" 2>/dev/null || true
    rm -f "/tmp/.X${PROBE_DISPLAY}-lock" "/tmp/.X11-unix/X${PROBE_DISPLAY}" 2>/dev/null || true
    # GL_EXTENSIONS is a single enormous line; it would bury the diagnosis.
    printf '%s' "$probe_out" | grep -v '^GL_EXTENSIONS' | cut -c1-200 > /tmp/.wt-gpu-probe.log
    printf '%s' "$renderer"
}

if [ "${GALLIUM_DRIVER:-}" = "virpipe" ]; then
    echo "[gpu] virgl socket present — probing GL before using it..."
    PROBE=""
    if gpu_ensure_packages; then
        CAN_CHECK=1
        PROBE="$(gpu_probe_renderer || true)"
    else
        CAN_CHECK=0
    fi

    if [ "$CAN_CHECK" = 0 ]; then
        # Not the same as a failed probe: we learned nothing about whether virgl
        # works, we just had no way to ask. Claiming a cause here would be
        # asserting something we never established.
        echo "[gpu] cannot verify GL — mesa-utils unavailable and could not be"
        echo "[gpu] installed (no network?). Staying on software rendering."
        echo "[gpu] Fix: run this in the app console, then relaunch --"
        echo "[gpu]   apt-get update && apt-get install -y mesa-utils libgl1-mesa-dri"
        export GALLIUM_DRIVER=llvmpipe
        export LIBGL_ALWAYS_SOFTWARE=1
    elif [ -n "$PROBE" ]; then
        echo "[gpu] renderer: $PROBE"
        echo "[gpu] hardware rendering enabled"
    else
        # GL really was attempted through virgl and did not come back. Echo what
        # the client actually said rather than guessing at a cause — the first
        # time this fired, the guess sent us after the wrong thing entirely.
        echo "[gpu] GL did not come up through virgl — falling back to llvmpipe."
        if [ -s /tmp/.wt-gpu-probe.log ]; then
            echo "[gpu] client said:"
            head -6 /tmp/.wt-gpu-probe.log | sed 's/^/[gpu]   /'
        fi
        export GALLIUM_DRIVER=llvmpipe
        export LIBGL_ALWAYS_SOFTWARE=1
    fi
else
    echo "[gpu] software rendering (WT_GPU=${WT_GPU:-off})"
fi

echo "[desktop] display backend: ${WT_DISPLAY_BACKEND:-vnc}"
if [ "${WT_DISPLAY_BACKEND:-vnc}" = "fb" ]; then
    # Shared-framebuffer backend. Xvfb writes the screen to an mmap'd file the
    # app reads directly; input goes to the X server over its socket via XTEST.
    # None of RFB's per-frame encode/socket/decode is in the loop.
    echo "[desktop] Starting ${WT_DE_NAME:-the desktop} on :$DISPLAY_NUM (${GEOMETRY}), framebuffer backend..."
    FBDIR=/tmp/.wt-fb

    # Xvfb is a separate package from tigervnc (Xvnc), so a container set up for
    # the VNC backend does not have it. Install it once, the same top-up trick
    # used for mesa-utils — otherwise the command below is simply not found.
    if ! command -v Xvfb >/dev/null 2>&1; then
        echo "[desktop] Xvfb not present — installing it once..."
        export DEBIAN_FRONTEND=noninteractive
        (apt-get update -y && apt-get install -y --no-install-recommends xvfb) \
            >/tmp/.wt-xvfb-install.log 2>&1 || true
    fi
    if ! command -v Xvfb >/dev/null 2>&1; then
        echo "[desktop] Xvfb could not be installed (no network?). Falling back to VNC." >&2
        echo "[desktop] Run this in the app console, then relaunch:" >&2
        echo "[desktop]   apt-get update && apt-get install -y xvfb" >&2
        WT_DISPLAY_BACKEND=vnc
    fi
fi

if [ "${WT_DISPLAY_BACKEND:-vnc}" = "fb" ]; then
    # A stale Xvfb from a previous session still holds the display, and the new
    # one then fails with "server already running". Kill it via the shared /proc.
    # Match on the cmdline read through tr+case, not `grep /Xvfb`: Xvfb is found
    # on PATH so its argv0 is "Xvfb" with no slash, and a bare grep pattern would
    # also match grep's own process. Escalate TERM then KILL so a wedged server
    # still goes down before we reclaim the display.
    # Read cmdline with $(<file), which bash does WITHOUT forking, and match with
    # a case builtin — so this scan spawns no processes. That matters here: it
    # runs after heavy sessions, and if the process budget is already tight (the
    # "fork: Function not implemented" failure), a scan that forks a `tr` per pid
    # cannot even run. NULs collapse under $(<...), which is fine for a substring.
    for sig in TERM KILL; do
        for p in /proc/[0-9]*; do
            [ -r "$p/cmdline" ] || continue
            { cmd=$(<"$p/cmdline"); } 2>/dev/null   # group redirect hides the NUL-byte warning
            case "$cmd" in
                *Xvfb*) kill -"$sig" "${p#/proc/}" 2>/dev/null || true ;;
            esac
        done
    done
    rm -f "/tmp/.X${DISPLAY_NUM}-lock" "/tmp/.X11-unix/X${DISPLAY_NUM}" 2>/dev/null || true
    rm -rf "$FBDIR"; mkdir -p "$FBDIR"

    # -ac: no access control — the socket lives only inside the app sandbox, the
    # same trust boundary the VNC path assumes with SecurityTypes None.
    Xvfb ":$DISPLAY_NUM" -screen 0 "${GEOMETRY}x${DEPTH}" -fbdir "$FBDIR" -ac \
        >/tmp/.wt-xvfb.log 2>&1 &
    i=0; while [ "$i" -lt 30 ]; do
        [ -e "/tmp/.X11-unix/X${DISPLAY_NUM}" ] && break; i=$((i + 1)); sleep 0.5
    done

    # The session (XFCE etc.) is a separate process here — Xvfb is only the
    # display server, unlike vncserver which runs xstartup itself.
    DISPLAY=":$DISPLAY_NUM" dbus-launch --exit-with-session $SESSION_LAUNCH \
        >/tmp/.wt-session.log 2>&1 &

    i=0; while [ "$i" -lt 30 ]; do
        [ -f "$FBDIR/Xvfb_screen0" ] && break; i=$((i + 1)); sleep 0.5
    done
    if [ ! -f "$FBDIR/Xvfb_screen0" ]; then
        echo "[desktop] framebuffer did not appear. Xvfb said:" >&2
        sed 's/^/[desktop]   /' /tmp/.wt-xvfb.log 2>/dev/null | head -8 >&2
        exit 1
    fi
    echo "[desktop] ${WT_DE_NAME:-Desktop} is running (framebuffer backend)."
    # Guest paths; the app maps /tmp -> WT_HOME/tmp to reach them on the host.
    echo "FB_READY $FBDIR/Xvfb_screen0 /tmp/.X11-unix/X${DISPLAY_NUM}"
    session_health_report "/tmp" ".wt-session.log"
else
    echo "[desktop] Starting ${WT_DE_NAME:-the desktop} on :$DISPLAY_NUM (${GEOMETRY})..."
    # -localhost: only the on-device app can reach it. SecurityTypes None because
    # the socket never leaves localhost inside the app sandbox.
    vncserver ":$DISPLAY_NUM" \
        -geometry "$GEOMETRY" \
        -depth "$DEPTH" \
        -localhost yes \
        -SecurityTypes None

    echo "[desktop] ${WT_DE_NAME:-Desktop} is running."
    echo "VNC_READY 127.0.0.1:$PORT"
    session_health_report "/root/.vnc" ":${DISPLAY_NUM}.log"
fi
