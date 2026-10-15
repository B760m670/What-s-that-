#!/bin/bash
# fix-process-limit.sh — runs INSIDE the container (via run-in-ubuntu.sh).
#
# Turns off Android's phantom-process killing, which is what ends a desktop
# session with SIGKILL while two gigabytes of memory sit free. Since Android 12
# the system allows an app only 32 processes outside its own management — all
# apps together — and kills them when that is passed. A GNOME session is forty
# on its own, so the cap is not something a lighter desktop can get under.
#
# The setting is `settings put global settings_enable_monitor_phantom_procs
# false`, and writing it needs WRITE_SECURE_SETTINGS, which no ordinary app can
# hold. The one way an app can reach it is the same way a developer would: adb.
# And adb does not need a computer — Android's wireless debugging listens on the
# device itself, so adb running in this container can talk to the phone it is
# running on, over loopback.
#
# The app supplies the ports and the pairing code; everything else happens here.
#
#   WT_ADB_PAIR_PORT     the pairing port (from the pairing dialog)
#   WT_ADB_PAIR_CODE     the six digits shown beside it
#   WT_ADB_CONNECT_PORT  the connection port (from the main screen); optional,
#                        discovered here if absent
#   WT_APP_PKG           this app, to be granted the permission for next time
set -u

PAIR_PORT="${WT_ADB_PAIR_PORT:-}"
PAIR_CODE="${WT_ADB_PAIR_CODE:-}"
CONNECT_PORT="${WT_ADB_CONNECT_PORT:-}"
PKG="${WT_APP_PKG:-com.whatsthat.linux}"
SETTING=settings_enable_monitor_phantom_procs

have() { command -v "$1" >/dev/null 2>&1; }

# install-desktop.sh ends by deleting /var/lib/apt/lists, so a later install
# finds nothing and reports every package as unavailable. Refill first.
ensure_apt_lists() {
    if [ -z "$(ls -A /var/lib/apt/lists 2>/dev/null | grep -v '^lock$\|^partial$' || true)" ]; then
        echo "[fix] refreshing package lists…"
        apt-get update -y >/dev/null 2>&1 || apt-get update -y || true
    fi
}

ensure_adb() {
    if have adb; then return 0; fi
    echo "[fix] installing adb…"
    ensure_apt_lists
    export DEBIAN_FRONTEND=noninteractive
    apt-get install -y --no-install-recommends adb >/dev/null 2>&1 ||
        apt-get install -y --no-install-recommends android-tools-adb >/dev/null 2>&1 || true
    have adb
}

# The connection port, if the app could not discover it. adb carries its own
# mDNS client, so it can often find what the app's discovery missed.
discover_connect_port() {
    local line port
    line=$(adb mdns services 2>/dev/null | grep -m1 '_adb-tls-connect' || true)
    port=$(printf '%s\n' "$line" | grep -oE ':[0-9]+' | head -1 | tr -d ':')
    printf '%s' "${port:-}"
}

WT_REQUIRED_FUNCTIONS="have ensure_apt_lists ensure_adb discover_connect_port"
for _f in $WT_REQUIRED_FUNCTIONS; do
    if ! declare -F "$_f" >/dev/null; then
        echo "[fix] INTERNAL ERROR: this script is missing $_f."
        echo "[fix] RESULT failed internal"
        exit 1
    fi
done

if [ -z "$PAIR_PORT" ] || [ -z "$PAIR_CODE" ]; then
    echo "[fix] No pairing port or code was given."
    echo "[fix] RESULT failed nopair"
    exit 1
fi

if ! ensure_adb; then
    echo "[fix] adb could not be installed — is there a network?"
    echo "[fix] RESULT failed noadb"
    exit 1
fi

adb kill-server >/dev/null 2>&1 || true

echo "[fix] pairing with the phone on port $PAIR_PORT…"
if ! adb pair "127.0.0.1:$PAIR_PORT" "$PAIR_CODE" 2>&1 | sed 's/^/[adb] /'; then
    echo "[fix] RESULT failed pair"
    exit 1
fi

if [ -z "$CONNECT_PORT" ]; then
    echo "[fix] looking for the connection port…"
    CONNECT_PORT=$(discover_connect_port)
fi
if [ -z "$CONNECT_PORT" ]; then
    echo "[fix] The connection port could not be found. It is on the Wireless debugging screen."
    echo "[fix] RESULT failed noport"
    exit 1
fi

echo "[fix] connecting on port $CONNECT_PORT…"
adb connect "127.0.0.1:$CONNECT_PORT" 2>&1 | sed 's/^/[adb] /'

# Pairing can succeed a moment before the connection is usable.
for _i in 1 2 3 4 5; do
    state=$(adb get-state 2>/dev/null || true)
    [ "$state" = device ] && break
    sleep 1
done
if [ "${state:-}" != device ]; then
    echo "[fix] Paired, but the connection did not come up."
    echo "[fix] RESULT failed connect"
    exit 1
fi

echo "[fix] turning off phantom-process killing…"
adb shell settings put global "$SETTING" false 2>&1 | sed 's/^/[adb] /'

# Android 12.0 (not 12L) needs the limit raised as well, and the order matters:
# without disabling the sync first, Play Services restores the ceiling on the
# next boot. Both are harmless where only the flag is needed.
adb shell /system/bin/device_config set_sync_disabled_for_tests persistent >/dev/null 2>&1 || true
adb shell /system/bin/device_config put activity_manager max_phantom_processes 2147483647 >/dev/null 2>&1 || true

# So this never has to be done again: with WRITE_SECURE_SETTINGS the app can
# read and set it directly, and the user never sees wireless debugging twice.
adb shell pm grant "$PKG" android.permission.WRITE_SECURE_SETTINGS >/dev/null 2>&1 &&
    echo "[fix] the app can now change this setting on its own." ||
    echo "[fix] (could not grant the app permission for next time — not fatal)"

now=$(adb shell settings get global "$SETTING" 2>/dev/null | tr -d '\r\n ')
echo "[fix] setting is now: ${now:-unset}"

adb disconnect >/dev/null 2>&1 || true
adb kill-server >/dev/null 2>&1 || true

case "$now" in
    false) echo "[fix] RESULT ok" ;;
    *)     echo "[fix] RESULT failed verify" ;;
esac
