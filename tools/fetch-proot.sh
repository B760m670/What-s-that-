#!/usr/bin/env bash
# fetch-proot.sh — populate app/src/main/jniLibs/<abi>/libproot.so
#
# proot is the open-source (GPLv2) engine that runs the Ubuntu rootfs without
# root. We reuse the prebuilt binaries from the Termux project rather than
# cross-compiling, then drop them in as native libs so Android's installer
# marks them executable. Re-run when bumping the proot version.
#
# Robust extraction: Termux .deb data members may be compressed with xz, gzip
# or zstd, so prefer `dpkg-deb -x` and fall back to ar + tar (any inner
# compression). Run from the repo root. Requires: curl, and dpkg-deb OR ar+tar.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JNILIBS="$REPO_ROOT/app/src/main/jniLibs"
TERMUX_POOL="https://packages.termux.dev/apt/termux-main/pool/main/p/proot"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# Map Android ABI dir  ->  Termux package arch.
declare -A ABI_TO_ARCH=(
    [arm64-v8a]=aarch64
    [armeabi-v7a]=arm
    [x86_64]=x86_64
)

extract_deb() {  # $1=deb path  $2=dest dir
    mkdir -p "$2"
    if command -v dpkg-deb >/dev/null 2>&1; then
        dpkg-deb -x "$1" "$2"
        return
    fi
    # Fallback: ar + tar, handling whatever compression the data member uses.
    ( cd "$2" && ar x "$1" )
    local data
    data="$(find "$2" -maxdepth 1 -name 'data.tar.*' | head -1)"
    tar -xf "$data" -C "$2"
}

echo "Resolving latest proot package from Termux..."
INDEX="$(curl -fsSL "$TERMUX_POOL/")"

found_any=0
for abi in "${!ABI_TO_ARCH[@]}"; do
    arch="${ABI_TO_ARCH[$abi]}"
    deb="$(printf '%s\n' "$INDEX" | grep -oE "proot_[^\"]*_${arch}\.deb" | sort -V | tail -1 || true)"
    if [ -z "$deb" ]; then
        echo "!! Could not find proot .deb for $arch — skipping $abi" >&2
        continue
    fi
    echo "==> $abi : $deb"
    curl -fsSL -o "$WORK/$deb" "$TERMUX_POOL/$deb"
    dest="$WORK/x_$abi"
    extract_deb "$WORK/$deb" "$dest"
    # Termux installs to data/data/com.termux/files/usr/bin/proot
    src="$(find "$dest" -path '*/bin/proot' -type f | head -1)"
    if [ -z "$src" ]; then
        echo "!! proot binary not found inside $deb" >&2
        exit 1
    fi
    install -Dm755 "$src" "$JNILIBS/$abi/libproot.so"
    found_any=1
done

if [ "$found_any" != 1 ]; then
    echo "ERROR: no proot binaries were fetched." >&2
    exit 1
fi
echo "Done. proot binaries placed under $JNILIBS/*/libproot.so"
