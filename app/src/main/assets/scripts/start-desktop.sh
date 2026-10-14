#!/bin/bash
# start-desktop.sh — runs INSIDE the container (via run-in-ubuntu.sh).
#
# Starts (or restarts) a desktop session on a VNC display and prints the
# loopback address the app's built-in viewer connects to.
#
# Two desktops and, for GNOME, two session types:
#
#   xfce            startxfce4 on the VNC X server, as it has always been.
#   gnome + x11     gnome-shell as an X11 compositing window manager, i.e. the
#                   classic "GNOME on Xorg" session, on the VNC X server.
#   gnome + wayland gnome-shell as a real Wayland compositor, nested inside a
#                   window on the VNC X server. Applications speak the Wayland
#                   protocol to mutter; X11 programs go through Xwayland, which
#                   the shell starts itself. The VNC server stops being the
#                   desktop and becomes only the pane the compositor draws into.
#
# The Wayland path exists because GNOME removed mutter's X11 backend in version
# 49 — from there on the X11 session is not deprecated, it is deleted. Nested is
# how a genuine Wayland GNOME still reaches a VNC transport today.
set -eu

DISPLAY_NUM=1
GEOMETRY="${WT_GEOMETRY:-1280x720}"
DEPTH=24
PORT=$((5900 + DISPLAY_NUM))
WANT_DE="${WT_DE:-xfce}"
WANT_SESSION="${WT_SESSION_TYPE:-x11}"
SESSION_LOG=/tmp/wt-session.log

export HOME=/root
export XDG_RUNTIME_DIR=/tmp/runtime-root

# --- tear down whatever is on the display ------------------------------------
#
# Same criterion as kill-session.sh: under proot every guest process is a ptrace
# tracee, so TracerPid names them all without a list of process names that would
# have to grow with every desktop. This matters more than it looks — /tmp is
# bound from one directory for ALL distros, so display :1, its lock file and
# XDG_RUNTIME_DIR are shared, and a session left behind by the *other* distro
# holds them just as firmly as one of our own.
FIELD=""
read_field() {   # $1=file  $2=field prefix
    FIELD=""
    local line
    while IFS= read -r line; do
        case $line in
            "$2"*) line=${line#"$2"}; FIELD=${line//[[:space:]]/}; return 0 ;;
        esac
    done < "$1"
    return 1
}

PROTECT=" $$ "
_p=$$
while [ -n "$_p" ] && [ "$_p" != 0 ] && [ "$_p" != 1 ]; do
    if read_field "/proc/$_p/status" "PPid:" 2>/dev/null; then
        _p=$FIELD
        PROTECT="$PROTECT$_p "
    else
        break
    fi
done

sweep() {   # $1 = signal
    local d pid
    for d in /proc/[0-9]*; do
        pid=${d#/proc/}
        case "$PROTECT" in *" $pid "*) continue ;; esac
        [ -r "$d/status" ] || continue
        if read_field "$d/status" "TracerPid:" 2>/dev/null; then
            if [ "$FIELD" != 0 ]; then
                kill -"$1" "$pid" 2>/dev/null || true
            fi
        fi
    done
}

vncserver -kill ":$DISPLAY_NUM" >/dev/null 2>&1 || true
sweep TERM
sleep 2 || true
sweep KILL
rm -f "/tmp/.X${DISPLAY_NUM}-lock" "/tmp/.X11-unix/X${DISPLAY_NUM}" 2>/dev/null || true
rm -f "/root/.vnc/"*":${DISPLAY_NUM}.pid" 2>/dev/null || true

mkdir -p "$XDG_RUNTIME_DIR" && chmod 700 "$XDG_RUNTIME_DIR" 2>/dev/null || true
# A relaunch, especially after switching distros, otherwise inherits a broken
# ICE/X authority and the session manager refuses to start.
rm -f /root/.ICEauthority /root/.Xauthority 2>/dev/null || true
rm -f "$SESSION_LOG" 2>/dev/null || true

# --- decide what to start ----------------------------------------------------
#
# Honour the app's choice only if it is actually present in THIS rootfs. The
# desktop is installed per rootfs, so a distro the user just switched to may not
# have the one they picked; starting the desktop that is there beats starting
# nothing and reporting a blank screen.
have() { command -v "$1" >/dev/null 2>&1; }

DE=""
case "$WANT_DE" in
    gnome) if have gnome-shell; then DE=gnome; fi ;;
    xfce)  if have startxfce4;  then DE=xfce;  fi ;;
esac
if [ -z "$DE" ]; then
    case "$WANT_DE" in
        gnome) echo "[desktop] GNOME is not installed in this system — starting what is." ;;
        xfce)  echo "[desktop] XFCE is not installed in this system — starting what is." ;;
    esac
    if have startxfce4;      then DE=xfce
    elif have gnome-shell;   then DE=gnome
    elif have openbox-session; then DE=openbox
    else DE=xterm
    fi
fi

# The window check needs xwininfo. A system whose desktop was installed before
# that check existed does not have it, so fetch it once — it is a quarter of a
# megabyte, and without it the only thing we can tell about a black screen is
# that the process is running, which is what made the last one undiagnosable.
if ! have xwininfo; then
    echo "[desktop] fetching x11-utils for the window check (one-off)..."
    timeout 120 apt-get install -y --no-install-recommends x11-utils >/dev/null 2>&1 || \
        echo "[desktop] x11-utils unavailable — the window check will be skipped."
fi

echo "[desktop] desktop: $DE"

# --- GNOME: settings that must be true before the first frame -----------------
#
# No session definition any more. We used to ship a .session file with a single
# required component, to keep gnome-session away from the stock list of
# seventeen; that was a real problem but not the last one, and running the shell
# directly (see attempt()) removes gnome-session from the picture entirely.
setup_gnome() {   # $1 = session type
    # Settings that must hold before the first frame, so they go into a system
    # dconf database: setting them with gsettings would need a session bus that
    # does not exist yet, while dconf update needs nothing at all.
    #
    # The lock screen is not a preference here, it is a trap: unlocking goes
    # through logind, which does not exist, so a session that locks itself can
    # never be unlocked. Animations are off because every frame of them is drawn
    # by llvmpipe on the CPU and then re-encoded by the VNC server.
    mkdir -p /etc/dconf/profile /etc/dconf/db/local.d
    printf 'user-db:user\nsystem-db:local\n' > /etc/dconf/profile/user
    cat > /etc/dconf/db/local.d/00-whatsthat <<'EOF'
[org/gnome/desktop/screensaver]
lock-enabled=false
idle-activation-enabled=false

[org/gnome/desktop/session]
idle-delay=uint32 0

[org/gnome/desktop/interface]
enable-animations=false

[org/gnome/mutter]
check-alive-timeout=uint32 60000
EOF
    dconf update 2>/dev/null || echo "[desktop] dconf update failed — lock screen may be active."
}

# gnome-shell dies without a SYSTEM D-Bus, and this is not a guess: its
# _initializeUI builds an EndSessionDialog, that reaches for Gio.DBus.system,
# and with no socket at /run/dbus/system_bus_socket the getter throws
#
#   JS ERROR: Gio.IOErrorEnum: Could not connect: No such file or directory
#     _init@resource:///org/gnome/shell/ui/endSessionDialog.js:244
#     _initializeUI@resource:///org/gnome/shell/ui/main.js:300
#
# which escapes main.js and takes the shell with it. The session bus we already
# had is a different bus; it does not help.
#
# Note what is NOT needed: logind, accountsservice, polkit. The shell copes with
# those names having no owner. It cannot cope with there being no bus to ask.
start_system_bus() {
    mkdir -p /run/dbus 2>/dev/null || true
    rm -f /run/dbus/pid 2>/dev/null || true
    if [ -S /run/dbus/system_bus_socket ]; then
        echo "[desktop] system D-Bus already up."
        return 0
    fi

    dbus-daemon --system --fork >/dev/null 2>/tmp/.wt-sysbus.err || true
    if [ -S /run/dbus/system_bus_socket ]; then
        echo "[desktop] system D-Bus started."
        return 0
    fi

    # The packaged system bus drops privileges to the messagebus user, which is
    # one more thing that can fail under a fake root. If it did, run a bus of our
    # own on the same socket: the shell only needs something to connect to.
    echo "[desktop] packaged system bus did not start; using a private one."
    sed 's/^/[desktop]   /' /tmp/.wt-sysbus.err 2>/dev/null | head -3
    cat > /etc/dbus-1/wt-system.conf <<'DBEOF'
<!DOCTYPE busconfig PUBLIC "-//freedesktop//DTD D-Bus Bus Configuration 1.0//EN"
 "http://www.freedesktop.org/standards/dbus/1.0/busconfig.dtd">
<busconfig>
  <type>system</type>
  <listen>unix:path=/run/dbus/system_bus_socket</listen>
  <policy context="default">
    <allow user="*"/>
    <allow own="*"/>
    <allow send_type="method_call"/>
    <allow send_type="signal"/>
    <allow send_type="method_return"/>
    <allow send_type="error"/>
    <allow receive_type="method_call"/>
    <allow receive_type="signal"/>
    <allow receive_type="method_return"/>
    <allow receive_type="error"/>
  </policy>
  <includedir>system.d</includedir>
</busconfig>
DBEOF
    dbus-daemon --config-file=/etc/dbus-1/wt-system.conf --fork >/dev/null 2>&1 || true
    if [ -S /run/dbus/system_bus_socket ]; then
        echo "[desktop] private system D-Bus started."
    else
        echo "[desktop] no system D-Bus — GNOME will not start without one."
    fi
}

# One full attempt: configure, write xstartup, start the server, verify.
# Returns 0 only if a desktop is actually running at the end of it.
attempt() {   # $1 = session type (x11|wayland)
    local stype="$1" session_cmd session_env=""

    case "$DE" in
        gnome)
            setup_gnome "$stype"
            # The shell is started DIRECTLY, without gnome-session, and that is
            # not a simplification — it is the fix for the fail whale.
            #
            # gnome-session watches its children with g_child_watch_source_new().
            # GLib 2.80 implements that with pidfd_open() + waitid(P_PIDFD, ...),
            # which under proot returns EINVAL, and glib/gmain.c:5802 reacts like
            # this:
            #
            #     g_warning (... "waitid(pid:%d, pidfd=%d) failed: ...");
            #     /* Assume the process is gone and proceed. */
            #     child_exited = TRUE;
            #
            # So GLib reports living children as dead. gnome-session is then told
            # its required component has exited, concludes the session has failed
            # and tears it down — "Oh no! Something has gone wrong". Nothing was
            # actually wrong with GNOME; the session manager was being lied to
            # about its own children.
            #
            # Running the shell directly removes the watcher, and with it the
            # entire class of failure. What we give up is gnome-session's
            # autostart and logout handling, neither of which works here anyway.
            session_cmd="gnome-shell"
            if [ "$stype" = "wayland" ]; then session_cmd="gnome-shell --wayland --nested"; fi
            session_env="XDG_CURRENT_DESKTOP=GNOME"
            ;;
        xfce)    session_cmd="startxfce4" ;;
        openbox) session_cmd="openbox-session" ;;
        *)       session_cmd="xterm" ;;
    esac

    rm -f "$SESSION_LOG" 2>/dev/null || true
    # gnome-shell writes this marker at startup and removes it on a clean exit;
    # finding it there disables every extension on the next run. A session we
    # killed never got to remove it.
    rm -f "$XDG_RUNTIME_DIR/gnome-shell-disable-extensions" 2>/dev/null || true
    start_system_bus
    start_audio

    # Regenerated every launch so fixes reach an installed system without
    # reinstalling it. Everything the session prints goes to one fixed path in
    # the shared /tmp, which the app can also read straight off its own
    # filesystem — no guessing at TigerVNC's log filename, which is how an
    # earlier round ended up with no diagnosis at all.
    local dbus_wrapper="dbus-launch --exit-with-session"
    if have dbus-run-session; then dbus_wrapper="dbus-run-session --"; fi

    mkdir -p /root/.vnc
    {
        echo '#!/bin/bash'
        echo "exec > $SESSION_LOG 2>&1"
        echo 'unset SESSION_MANAGER'
        echo 'unset DBUS_SESSION_BUS_ADDRESS'
        # Several GNOME wrappers re-exec themselves through a login shell when
        # XDG_SESSION_TYPE=wayland and SHELL is set. Nothing to gain here, a
        # whole environment to lose.
        echo 'unset SHELL'
        echo 'export HOME=/root'
        echo "export XDG_RUNTIME_DIR=$XDG_RUNTIME_DIR"
        echo 'export XKL_XMODMAP_DISABLE=1'
        echo 'export NO_AT_BRIDGE=1'
        # No DRI device exists behind Xvnc, so this only makes the fallback
        # explicit rather than discovered. llvmpipe is a renderer GNOME supports
        # on purpose: its own hardware-compatibility list blacklists softpipe and
        # whitelists llvmpipe by name.
        echo 'export LIBGL_ALWAYS_SOFTWARE=1'
        # GTK4 defaults to a Vulkan renderer with nothing to run on here, and
        # degrades badly when it falls back; cairo is the honest choice on a CPU
        # rasteriser and is visibly faster for Files, Text Editor and Settings.
        echo 'export GSK_RENDERER=cairo'
        if [ -n "$session_env" ]; then echo "export $session_env"; fi
        if [ "$stype" = "wayland" ]; then
            echo 'export XDG_SESSION_TYPE=wayland'
            # The nested compositor has no monitor to ask, so it takes its size
            # from here. Matching the VNC geometry makes it fill the screen.
            echo "export MUTTER_DEBUG_DUMMY_MODE_SPECS=$GEOMETRY"
        else
            echo 'export XDG_SESSION_TYPE=x11'
        fi
        if [ "$DE" = "gnome" ]; then
            # Without gnome-session nothing starts the settings daemons. This is
            # the only one worth having: it is what gives GTK applications their
            # theme, fonts and scaling. It has to run INSIDE the session bus, so
            # it goes after the dbus wrapper, not before it — and backgrounded
            # and unwatched, so it cannot take the session with it if it fails.
            echo "exec $dbus_wrapper bash -c '[ -x /usr/libexec/gsd-xsettings ] && /usr/libexec/gsd-xsettings & exec $session_cmd'"
        else
            echo "exec $dbus_wrapper $session_cmd"
        fi
    } > /root/.vnc/xstartup
    chmod +x /root/.vnc/xstartup

    echo "[desktop] trying $DE ($stype): $session_cmd"
    local rc=0
    vncserver ":$DISPLAY_NUM" \
        -geometry "$GEOMETRY" \
        -depth "$DEPTH" \
        -localhost yes \
        -SecurityTypes None || rc=$?

    if [ "$rc" != 0 ]; then
        echo "[desktop] the VNC server could not bring the session up (exit $rc)."
        report_session
        return 1
    fi

    if wait_for_session; then
        local win
        win=$(mapped_windows)
        echo "[desktop] $DE ($stype) is running; mapped windows: $win"
        if [ "$win" = 0 ]; then
            echo "[desktop] the session is alive but has drawn nothing — a black"
            echo "[desktop] screen. Treating that as a failure, not a success."
            report_session
            return 1
        fi
        report_session
        return 0
    fi

    if [ "$WHALE" = yes ]; then
        echo "[desktop] GNOME came up and then failed (its \"Oh no\" screen)."
    else
        echo "[desktop] the session did not stay running."
    fi
    report_session
    return 1
}

# Openbox on its own shows only a bare background and a cursor, and touch VNC
# has no right-click to reach its menu. Autostart a panel and a terminal so
# there is an immediately usable desktop (e.g. to run `wine notepad`).
if [ "$DE" = "openbox" ]; then
    mkdir -p /root/.config/openbox
    cat > /root/.config/openbox/autostart <<'OBEOF'
command -v tint2 >/dev/null 2>&1 && tint2 &
command -v xterm >/dev/null 2>&1 && xterm -geometry 110x30+30+30 &
OBEOF
fi

# --- audio -------------------------------------------------------------------
# The container can't reach the speaker directly. Run PulseAudio here, route
# everything to a null sink, and stream that sink's PCM over loopback TCP
# (s16le/48k/stereo) — the app's AudioBridge reads 127.0.0.1:4712 and plays it.
#
# Per attempt, not once: the teardown between attempts reaps every process in
# the container, PulseAudio included, and a second attempt without this got
# "pa_context_connect() failed: Connection refused".
start_audio() {
if have pulseaudio; then
    pulseaudio --kill >/dev/null 2>&1 || true
    sleep 1 || true
    pulseaudio --start --exit-idle-time=-1 --disable-shm=true >/dev/null 2>&1 || true
    sleep 1 || true
    pactl load-module module-null-sink sink_name=wt \
        sink_properties=device.description=WhatsThat >/dev/null 2>&1 || true
    pactl set-default-sink wt >/dev/null 2>&1 || true
    pactl load-module module-simple-protocol-tcp record=true source=wt.monitor \
        listen=127.0.0.1 port=4712 format=s16le rate=48000 channels=2 >/dev/null 2>&1 || true
fi
}

# Which session types to try, best guess first. For anything but GNOME there is
# only one way to run it. For GNOME, whatever worked last time is tried first —
# the app passes it back in — and the other is the fallback.
CANDIDATES="x11"
if [ "$DE" = "gnome" ]; then
    if [ "$WANT_SESSION" = "wayland" ]; then CANDIDATES="wayland x11"; else CANDIDATES="x11 wayland"; fi
fi

for stype in $CANDIDATES; do
    if attempt "$stype"; then
        # The app stores this and tries it first next time, so a device only
        # pays for the discovery once.
        echo "WT_SESSION_USED $stype"
        echo "VNC_READY 127.0.0.1:$PORT"
        exit 0
    fi
    echo "[desktop] cleaning up before the next attempt..."
    teardown
done

echo "[desktop] no session type worked. The log above is the reason."
exit 1
