#!/usr/bin/env bash
# fetch-proot.sh — populate app/src/main/jniLibs/<abi>/ with proot + its deps.
#
# Termux's proot is dynamically linked (needs libtalloc.so.2) and uses an
# external loader, so a standalone APK must ship all the pieces. We grab them
# from Termux's prebuilt .deb packages and drop them in as native libs (lib*.so)
# so Android extracts them, executable, into nativeLibraryDir:
#
#   libproot.so       <- usr/bin/proot
#   libloader.so      <- usr/libexec/proot/loader        (PROOT_LOADER)
#   libloader32.so    <- usr/libexec/proot/loader32      (PROOT_LOADER_32, if any)
#   libtalloc.so      <- real libtalloc.so.2.x  (symlinked to libtalloc.so.2 at runtime)
#   libandroid-shmem.so <- usr/lib/libandroid-shmem.so   (SysV shm shim)
#
# Run from the repo root. Requires: curl, and dpkg-deb OR ar+tar.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JNILIBS="$REPO_ROOT/app/src/main/jniLibs"
POOL="https://packages.termux.dev/apt/termux-main/pool/main"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# Android ABI dir -> Termux package arch.
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

pool_dir() {  # debian pool prefix: lib* -> first 4 chars, else first char
    case "$1" in
        lib*) printf '%s' "${1:0:4}" ;;
        *)    printf '%s' "${1:0:1}" ;;
    esac
}

# Download the newest .deb for a package+arch into $WORK and echo its path.
fetch_deb() {  # $1=pkg  $2=arch
    local pkg="$1" arch="$2" dir deb
    dir="$(pool_dir "$pkg")"
    local index; index="$(curl -fsSL "$POOL/$dir/$pkg/")"
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

    proot_deb="$(fetch_deb proot "$arch")" || continue
    talloc_deb="$(fetch_deb libtalloc "$arch")" || continue
    shmem_deb="$(fetch_deb libandroid-shmem "$arch")" || continue

    pe="$WORK/pe_$abi"; te="$WORK/te_$abi"; se="$WORK/se_$abi"
    extract_deb "$proot_deb" "$pe"
    extract_deb "$talloc_deb" "$te"
    extract_deb "$shmem_deb" "$se"

    proot_bin="$(find "$pe" -path '*/bin/proot' -type f | head -1)"
    loader="$(find "$pe" -path '*/libexec/proot/loader' -type f | head -1)"
    loader32="$(find "$pe" -path '*/libexec/proot/loader32' -type f | head -1)"
    talloc="$(find "$te" -name 'libtalloc.so.2*' -type f | head -1)"
    shmem="$(find "$se" -name 'libandroid-shmem.so' -type f | head -1)"

    [ -n "$proot_bin" ] || { echo "!! proot binary missing for $abi" >&2; exit 1; }
    [ -n "$talloc" ]    || { echo "!! libtalloc missing for $abi" >&2; exit 1; }
    [ -n "$shmem" ]     || { echo "!! libandroid-shmem missing for $abi" >&2; exit 1; }

    install -Dm755 "$proot_bin" "$out/libproot.so"
    install -Dm755 "$talloc"    "$out/libtalloc.so"
    install -Dm755 "$shmem"     "$out/libandroid-shmem.so"
    [ -n "$loader" ]   && install -Dm755 "$loader"   "$out/libloader.so"
    [ -n "$loader32" ] && install -Dm755 "$loader32" "$out/libloader32.so"

    # Diagnostic: show proot's shared-lib dependencies so any still-missing one
    # is visible in the build log (we already bundle libtalloc + libandroid-shmem).
    if command -v readelf >/dev/null 2>&1; then
        echo "    proot NEEDED:"; readelf -d "$out/libproot.so" 2>/dev/null | grep NEEDED || true
    fi
    found_any=1
done

[ "$found_any" = 1 ] || { echo "ERROR: nothing fetched." >&2; exit 1; }
echo "Done. proot + deps placed under $JNILIBS/*/"
