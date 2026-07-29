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
# desktop). Pick whatever session is installed.
if command -v startxfce4 >/dev/null 2>&1; then SESSION_CMD="startxfce4"
elif command -v openbox-session >/dev/null 2>&1; then SESSION_CMD="openbox-session"
elif command -v startlxqt >/dev/null 2>&1; then SESSION_CMD="startlxqt"
else SESSION_CMD="xterm"; fi

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
if command -v pulseaudio >/dev/null 2>&1; then
    pulseaudio --kill >/dev/null 2>&1 || true
    sleep 1
    pulseaudio --start --exit-idle-time=-1 --disable-shm=true >/dev/null 2>&1 || true
    sleep 1
    pactl load-module module-null-sink sink_name=wt \
        sink_properties=device.description=WhatsThat >/dev/null 2>&1 || true
    pactl set-default-sink wt >/dev/null 2>&1 || true
    pactl load-module module-simple-protocol-tcp record=true source=wt.monitor \
        listen=127.0.0.1 port=4712 format=s16le rate=48000 channels=2 >/dev/null 2>&1 || true
fi

mkdir -p /root/.vnc
cat > /root/.vnc/xstartup <<EOF
#!/bin/sh
unset SESSION_MANAGER
unset DBUS_SESSION_BUS_ADDRESS
export HOME=/root
export XDG_RUNTIME_DIR=/tmp/runtime-root
export XKL_XMODMAP_DISABLE=1
exec dbus-launch --exit-with-session $SESSION_CMD
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
    command -v glxinfo >/dev/null 2>&1 && return 0
    echo "[gpu] GL packages missing in this container — installing them once..."
    export DEBIAN_FRONTEND=noninteractive
    (apt-get update -y && apt-get install -y --no-install-recommends \
        libgl1-mesa-dri libglx-mesa0 mesa-utils) >/dev/null 2>&1 || true
    command -v glxinfo >/dev/null 2>&1
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
    # A crashing client exits non-zero and prints nothing, which is exactly the
    # signal we want; the timeout covers a server that hangs instead.
    probe_out="$(DISPLAY=":$PROBE_DISPLAY" timeout 20 glxinfo -B 2>/dev/null || true)"
    kill "$probe_pid" 2>/dev/null || true
    rm -f "/tmp/.X${PROBE_DISPLAY}-lock" "/tmp/.X11-unix/X${PROBE_DISPLAY}" 2>/dev/null || true
    printf '%s' "$probe_out" | grep -i 'OpenGL renderer' | head -1
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
        echo "[gpu] cannot verify GL — glxinfo unavailable and could not be"
        echo "[gpu] installed (no network?). Staying on software rendering."
        echo "[gpu] Fix: run this in the app console, then relaunch --"
        echo "[gpu]   apt-get update && apt-get install -y mesa-utils libgl1-mesa-dri"
        export GALLIUM_DRIVER=llvmpipe
        export LIBGL_ALWAYS_SOFTWARE=1
    elif [ -n "$PROBE" ]; then
        echo "[gpu] ${PROBE#*: }"
        echo "[gpu] hardware rendering enabled"
    else
        # GL really was attempted through virgl and did not come back. Report
        # that, with the candidate causes, without picking one on no evidence.
        echo "[gpu] GL did not come up through virgl — falling back to llvmpipe."
        echo "[gpu] Likely: vtest protocol mismatch between this distro's Mesa"
        echo "[gpu] and the bundled server, or the server has no GL on this device."
        export GALLIUM_DRIVER=llvmpipe
        export LIBGL_ALWAYS_SOFTWARE=1
    fi
else
    echo "[gpu] software rendering (WT_GPU=${WT_GPU:-off})"
fi

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
