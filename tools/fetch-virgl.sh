#!/usr/bin/env bash
# fetch-virgl.sh — populate app/src/main/jniLibs/<abi>/ with the virgl server.
#
# This is the host (Android/bionic) half of GPU acceleration. The container is
# glibc, so it can never dlopen the vendor GLES blob in /vendor/lib*; instead
# Mesa inside the container uses its `virpipe` gallium driver, which forwards GL
# commands over a UNIX socket to this server, which runs OUTSIDE proot as a
# normal bionic process and therefore *can* talk to the real driver.
#
# Same delivery trick as fetch-proot.sh: Termux's prebuilt .debs, renamed to
# lib*.so so Android extracts them into nativeLibraryDir, executable.
#
#   libvirglserver.so   <- usr/bin/virgl_test_server_android
#   libvirglrenderer.so <- usr/opt/virglrenderer-android/lib/libvirglrenderer.so
#   libepoxy.so         <- usr/opt/virglrenderer-android/lib/libepoxy.so
#
# Note both shared libs carry a RUNPATH pointing into /data/data/com.termux,
# which will not exist for us — GpuBridge sets LD_LIBRARY_PATH to
# nativeLibraryDir so the linker finds them by soname instead.
#
# Run from the repo root. Requires: curl, and dpkg-deb OR ar+tar.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JNILIBS="$REPO_ROOT/app/src/main/jniLibs"
POOL="https://packages.termux.dev/apt/termux-main/pool/main"
PKG="virglrenderer-android"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# Android ABI dir -> Termux package arch. Matches abiFilters in build.gradle.kts.
declare -A ABI_TO_ARCH=(
    [arm64-v8a]=aarch64
    [armeabi-v7a]=arm
    [x86_64]=x86_64
)

extract_deb() {  # $1=deb  $2=dest
    mkdir -p "$2"
    if command -v dpkg-deb >/dev/null 2>&1; then
        dpkg-deb -x "$1" "$2"
    else
        ( cd "$2" && ar x "$1" )
        tar -xf "$2"/data.tar.* -C "$2"
    fi
}

fetch_deb() {  # $1=pkg  $2=arch  -> echoes path to the newest matching .deb
    local pkg="$1" arch="$2" dir deb index
    dir="${pkg:0:1}"
    index="$(curl -fsSL "$POOL/$dir/$pkg/")"
    deb="$(printf '%s\n' "$index" | grep -oE "${pkg}_[^\"]*_${arch}\.deb" | sort -V | tail -1 || true)"
    [ -n "$deb" ] || { echo "!! no $pkg .deb for $arch" >&2; return 1; }
    curl -fsSL -o "$WORK/$deb" "$POOL/$dir/$pkg/$deb"
    printf '%s' "$WORK/$deb"
}

found_any=0
for abi in "${!ABI_TO_ARCH[@]}"; do
    arch="${ABI_TO_ARCH[$abi]}"
    echo "==> $abi ($arch)"
    out="$JNILIBS/$abi"; mkdir -p "$out"

    # GPU acceleration is an enhancement, not a hard requirement: if the package
    # is unavailable for an ABI, that build simply falls back to llvmpipe.
    deb="$(fetch_deb "$PKG" "$arch")" || { echo "   skipping $abi — no package"; continue; }

    ex="$WORK/ex_$abi"
    extract_deb "$deb" "$ex"

    server="$(find "$ex" -name 'virgl_test_server_android' -type f | head -1)"
    renderer="$(find "$ex" -name 'libvirglrenderer.so' -type f | head -1)"
    epoxy="$(find "$ex" -name 'libepoxy.so' -type f | head -1)"

    [ -n "$server" ]   || { echo "!! virgl server missing for $abi" >&2; exit 1; }
    [ -n "$renderer" ] || { echo "!! libvirglrenderer missing for $abi" >&2; exit 1; }
    [ -n "$epoxy" ]    || { echo "!! libepoxy missing for $abi" >&2; exit 1; }

    install -Dm755 "$server"   "$out/libvirglserver.so"
    install -Dm755 "$renderer" "$out/libvirglrenderer.so"
    install -Dm755 "$epoxy"    "$out/libepoxy.so"

    # Diagnostic: any NEEDED we don't ship would fail at exec time on-device,
    # where it is far harder to see than in a build log.
    if command -v readelf >/dev/null 2>&1; then
        echo "    virgl server NEEDED:"
        readelf -d "$out/libvirglserver.so" 2>/dev/null | grep NEEDED || true
    fi
    found_any=1
done

if [ "$found_any" = 1 ]; then
    echo "Done. virgl server + deps placed under $JNILIBS/*/"
else
    # Deliberately not an error: the app runs on llvmpipe without these.
    echo "WARNING: no virgl packages fetched — builds will be software-rendered only." >&2
fi
