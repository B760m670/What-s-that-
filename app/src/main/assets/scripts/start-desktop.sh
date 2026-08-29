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

SESSION_TYPE=x11
if [ "$DE" = "gnome" ] && [ "$WANT_SESSION" = "wayland" ]; then SESSION_TYPE=wayland; fi
echo "[desktop] desktop: $DE  session: $SESSION_TYPE"

# --- GNOME: our own session definition ---------------------------------------
#
# The stock gnome.session (and Ubuntu's ubuntu.session) lists seventeen
# RequiredComponents: the shell plus every gnome-settings-daemon plugin. In
# gnome-session, "required" is literal — `Unable to find required component`
# ends the session, and that is the "Oh no! Something has gone wrong" screen.
# Several of those plugins cannot work here at all: Power wants upower and
# logind, UsbProtection wants logind, Rfkill wants a radio, Smartcard wants
# pcscd. Under proot the stock session is therefore guaranteed to fail, and
# that is why the previous attempt at GNOME never showed a desktop.
#
# So we ship our own session with exactly one required component — the shell —
# and let the settings daemons come up through the ordinary XDG autostart
# directory, where a failure is not fatal. This also makes the launch identical
# on Debian and Ubuntu, which name and configure their stock sessions
# differently (gnome vs ubuntu, the latter also needing
# GNOME_SHELL_SESSION_MODE=ubuntu).
setup_gnome() {
    local args=""
    if [ "$SESSION_TYPE" = "wayland" ]; then args=" --wayland --nested"; fi

    mkdir -p /usr/share/gnome-session/sessions /usr/share/applications
    cat > /usr/share/gnome-session/sessions/whatsthat.session <<EOF
[GNOME Session]
Name=GNOME
RequiredComponents=whatsthat-shell;
DesktopName=GNOME
EOF

    # Mirrors GNOME's own org.gnome.Shell.desktop; only Exec differs. The
    # DisplayServer phase is what makes the rest of the session wait for the
    # compositor and pick up WAYLAND_DISPLAY from it.
    cat > /usr/share/applications/whatsthat-shell.desktop <<EOF
[Desktop Entry]
Type=Application
Name=GNOME Shell
Exec=/usr/bin/gnome-shell$args
NoDisplay=true
OnlyShowIn=GNOME;
X-GNOME-Autostart-Phase=DisplayServer
X-GNOME-Provides=panel;windowmanager;
X-GNOME-Autostart-Notify=true
X-GNOME-AutoRestart=false
EOF

    # Mask the settings-daemon plugins that cannot work without logind, upower
    # or real hardware. They would not kill the session from autostart, but they
    # retry, log and cost startup time on a phone that has none of it.
    mkdir -p /root/.config/autostart
    for p in Power Rfkill Smartcard UsbProtection Sharing Wacom Wwan \
             PrintNotifications ScreensaverProxy Color; do
        cat > "/root/.config/autostart/org.gnome.SettingsDaemon.$p.desktop" <<EOF
[Desktop Entry]
Type=Application
Name=$p (disabled here)
Hidden=true
EOF
    done

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

SESSION_ENV=""
case "$DE" in
    gnome)
        if have gnome-session; then
            setup_gnome
            SESSION_CMD="gnome-session --builtin --session=whatsthat"
        else
            # gnome-session-bin missing: the shell alone is still a usable
            # desktop (top bar, overview, app grid), just without the settings
            # daemons. Better than refusing to start.
            echo "[desktop] gnome-session is missing — starting the shell on its own."
            SESSION_CMD="gnome-shell"
            if [ "$SESSION_TYPE" = "wayland" ]; then
                SESSION_CMD="gnome-shell --wayland --nested"
            fi
        fi
        SESSION_ENV="XDG_CURRENT_DESKTOP=GNOME"
        ;;
    xfce)    SESSION_CMD="startxfce4" ;;
    openbox) SESSION_CMD="openbox-session" ;;
    *)       SESSION_CMD="xterm" ;;
esac

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

# --- audio -------------------------------------------------------------------
# The container can't reach the speaker directly. Run PulseAudio here, route
# everything to a null sink, and stream that sink's PCM over loopback TCP
# (s16le/48k/stereo) — the app's AudioBridge reads 127.0.0.1:4712 and plays it.
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

# --- the session itself ------------------------------------------------------
#
# Regenerated every launch so fixes apply without reinstalling the desktop.
#
# Everything the session prints goes to one fixed path in the shared /tmp, which
# the app can read straight off its own filesystem without entering the
# container. The previous round hunted for TigerVNC's own log by guessing at its
# filename, found nothing, and so a failed session reported precisely nothing.
DBUS_WRAPPER="dbus-launch --exit-with-session"
if have dbus-run-session; then DBUS_WRAPPER="dbus-run-session --"; fi

mkdir -p /root/.vnc
{
    echo '#!/bin/bash'
    echo "exec > $SESSION_LOG 2>&1"
    echo 'unset SESSION_MANAGER'
    echo 'unset DBUS_SESSION_BUS_ADDRESS'
    echo 'export HOME=/root'
    echo "export XDG_RUNTIME_DIR=$XDG_RUNTIME_DIR"
    echo 'export XKL_XMODMAP_DISABLE=1'
    echo 'export NO_AT_BRIDGE=1'
    # No DRI device exists behind Xvnc, so this only makes the fallback explicit
    # rather than discovered. llvmpipe is a renderer GNOME supports on purpose:
    # its own hardware-compatibility list blacklists softpipe and whitelists
    # llvmpipe by name.
    echo 'export LIBGL_ALWAYS_SOFTWARE=1'
    # GTK4 defaults to a Vulkan renderer that has nothing to run on here and
    # degrades badly when it falls back; cairo is the honest choice on a CPU
    # rasteriser and is visibly faster for Files, Text Editor and Settings.
    echo 'export GSK_RENDERER=cairo'
    if [ -n "$SESSION_ENV" ]; then echo "export $SESSION_ENV"; fi
    if [ "$SESSION_TYPE" = "wayland" ]; then
        echo 'export XDG_SESSION_TYPE=wayland'
        # The nested compositor has no monitor to ask, so it takes its size from
        # here. Matching the VNC geometry makes it fill the screen exactly.
        echo "export MUTTER_DEBUG_DUMMY_MODE_SPECS=$GEOMETRY"
    else
        echo 'export XDG_SESSION_TYPE=x11'
    fi
    echo "exec $DBUS_WRAPPER $SESSION_CMD"
} > /root/.vnc/xstartup
chmod +x /root/.vnc/xstartup

echo "[desktop] Starting $DE ($SESSION_TYPE) on :$DISPLAY_NUM (${GEOMETRY})..."
# -localhost: only the on-device app can reach it. SecurityTypes None because
# the socket never leaves localhost inside the app sandbox.
vncserver ":$DISPLAY_NUM" \
    -geometry "$GEOMETRY" \
    -depth "$DEPTH" \
    -localhost yes \
    -SecurityTypes None

echo "[desktop] display is up."
echo "VNC_READY 127.0.0.1:$PORT"

# --- did the session actually survive? ---------------------------------------
#
# "Desktop is running" only ever meant the X server came up; the session inside
# it can still die a second later, which is exactly what GNOME's fail whale is.
# This runs in the FOREGROUND after the READY line: the app reads until READY,
# opens the viewer, then drains the rest on a thread, and proot tears the
# container down the moment this script exits — so a backgrounded check would be
# killed before it could speak. That is not a guess; it is what happened.
sleep 10 || true
alive=no
for d in /proc/[0-9]*; do
    [ -r "$d/cmdline" ] || continue
    { c=$(<"$d/cmdline"); } 2>/dev/null || continue
    case "$c" in
        *gnome-shell*|*xfce4-session*|*openbox*) alive=yes; break ;;
    esac
done
echo "[session] desktop process alive: $alive"

if [ -r "$SESSION_LOG" ]; then
    mapfile -t _lines < "$SESSION_LOG" 2>/dev/null || _lines=()
    hits=0
    for l in "${_lines[@]}"; do
        case "$l" in
            *"Oh no"*|*"has gone wrong"*|*"Unable to find required component"*|\
            *"Failed to"*|*"failed to"*|*"cannot open"*|*"No such file"*|\
            *"Segmentation"*|*"libGL error"*|*"EGL"*|*"Unable to init"*)
                echo "[session]   ${l:0:170}"
                hits=$((hits + 1))
                if [ "$hits" -ge 12 ]; then break; fi ;;
        esac
    done
    if [ "$hits" = 0 ]; then
        echo "[session] no obvious errors; last lines of $SESSION_LOG:"
        n=${#_lines[@]}
        i=$((n > 6 ? n - 6 : 0))
        while [ "$i" -lt "$n" ]; do echo "[session]   ${_lines[$i]:0:170}"; i=$((i + 1)); done
    else
        echo "[session] ^ the desktop session reported the above."
    fi
else
    echo "[session] $SESSION_LOG was never created — the session did not start."
fi
