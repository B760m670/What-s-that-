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
setup_gnome() {   # $1 = session type
    local args=""
    if [ "$1" = "wayland" ]; then args=" --wayland --nested"; fi

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


# --- bringing a session up, and proving it stayed up -------------------------
#
# There used to be a setting asking the user to choose between X11 and nested
# Wayland. That was the wrong question to ask anyone: there is no way for a
# person to know which of the two their phone, their GNOME version and proot
# will tolerate, and being asked made them responsible for an answer only an
# attempt can give. So the app tries, checks, and falls back.

WHALE=no

# A desktop is "up" when its own process is running. gnome-session-failed is
# GNOME's fail whale: it appears precisely when the shell has died, so finding
# it is a definite failure rather than an absence of evidence.
session_alive() {
    local d c found=1
    WHALE=no
    for d in /proc/[0-9]*; do
        [ -r "$d/cmdline" ] || continue
        { c=$(<"$d/cmdline"); } 2>/dev/null || continue
        case "$c" in
            *gnome-session-failed*) WHALE=yes ;;
            *gnome-shell*|*xfce4-session*|*openbox-session*) found=0 ;;
        esac
    done
    return $found
}

# Wait for the session to appear AND still be there a moment later. Seen once is
# not proof: a shell that crashes on startup exists for a second or two first,
# and that is the exact case we are trying to tell apart.
wait_for_session() {
    local i
    for i in 1 2 3 4 5 6 7 8 9 10; do
        sleep 2 || true
        if [ "$WHALE" = yes ]; then return 1; fi
        if session_alive; then
            sleep 3 || true
            session_alive || return 1
            [ "$WHALE" = no ] || return 1
            return 0
        fi
    done
    return 1
}

teardown() {
    vncserver -kill ":$DISPLAY_NUM" >/dev/null 2>&1 || true
    sweep TERM
    sleep 1 || true
    sweep KILL
    rm -f "/tmp/.X${DISPLAY_NUM}-lock" "/tmp/.X11-unix/X${DISPLAY_NUM}" 2>/dev/null || true
    rm -f "/root/.vnc/"*":${DISPLAY_NUM}.pid" 2>/dev/null || true
}

# Print what the session itself said. This has to run on every failing path: the
# first version let `set -e` abort at a failing vncserver, so the one case that
# needed explaining was the one case that explained nothing.
report_session() {
    if [ -r "$SESSION_LOG" ] && [ -s "$SESSION_LOG" ]; then
        echo "=================== $SESSION_LOG ==================="
        mapfile -t _lines < "$SESSION_LOG" 2>/dev/null || _lines=()
        n=${#_lines[@]}
        i=$((n > 40 ? n - 40 : 0))
        while [ "$i" -lt "$n" ]; do echo "${_lines[$i]:0:200}"; i=$((i + 1)); done
        echo "==================================================="
    else
        echo "[session] $SESSION_LOG is empty or missing — the session command"
        echo "[session] did not get far enough to write to it."
    fi
}

# One full attempt: configure, write xstartup, start the server, verify.
# Returns 0 only if a desktop is actually running at the end of it.
attempt() {   # $1 = session type (x11|wayland)
    local stype="$1" session_cmd session_env="" gs_help gs_args

    case "$DE" in
        gnome)
            if have gnome-session; then
                setup_gnome "$stype"
                # Ask the installed gnome-session what options it has rather than
                # assuming. --builtin existed in GNOME 43 and was REMOVED by 46:
                # passing it there is "Unknown option", exit 1, and a session that
                # never starts. It is not needed either — gnome-session falls back
                # to the non-systemd startup procedure on its own.
                gs_help=$(gnome-session --help-all 2>&1 || true)
                gs_args="--session=whatsthat"
                case "$gs_help" in *--builtin*) gs_args="--builtin $gs_args" ;; esac
                # Under llvmpipe the acceleration check is a pure liability:
                # GNOME supports software rendering here, and a failed check can
                # only cost us the session.
                case "$gs_help" in
                    *--disable-acceleration-check*) gs_args="$gs_args --disable-acceleration-check" ;;
                esac
                session_cmd="gnome-session $gs_args"
            else
                echo "[desktop] gnome-session is missing — starting the shell on its own."
                session_cmd="gnome-shell"
                if [ "$stype" = "wayland" ]; then session_cmd="gnome-shell --wayland --nested"; fi
            fi
            session_env="XDG_CURRENT_DESKTOP=GNOME"
            ;;
        xfce)    session_cmd="startxfce4" ;;
        openbox) session_cmd="openbox-session" ;;
        *)       session_cmd="xterm" ;;
    esac

    rm -f "$SESSION_LOG" 2>/dev/null || true

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
        # /usr/bin/gnome-session re-execs itself through a login shell when
        # XDG_SESSION_TYPE=wayland and SHELL is set. Nothing to gain, a whole
        # environment to lose.
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
        echo "exec $dbus_wrapper $session_cmd"
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
        echo "[desktop] $DE ($stype) is running."
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
# Done once, before any attempt: it is independent of how the desktop draws.
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
