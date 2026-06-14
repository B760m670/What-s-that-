#!/usr/bin/env bash
# fetch-proot.sh — populate app/src/main/jniLibs/<abi>/libproot.so
#
# proot is the open-source (GPLv2) engine that runs the Ubuntu rootfs without
# root. We reuse the prebuilt binaries from the Termux project rather than
# cross-compiling, then drop them in as native libs so Android's installer
# marks them executable. Re-run when bumping the proot version.
#
# Requires: curl, ar, tar (xz support). Run from the repo root.
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

echo "Resolving latest proot package from Termux..."
INDEX="$(curl -fsSL "$TERMUX_POOL/")"

for abi in "${!ABI_TO_ARCH[@]}"; do
    arch="${ABI_TO_ARCH[$abi]}"
    deb="$(printf '%s\n' "$INDEX" | grep -oE "proot_[^\"]*_${arch}\.deb" | sort -V | tail -1)"
    if [ -z "$deb" ]; then
        echo "!! Could not find proot .deb for $arch — skipping $abi" >&2
        continue
    fi
    echo "==> $abi : $deb"
    curl -fsSL -o "$WORK/$deb" "$TERMUX_POOL/$deb"
    ( cd "$WORK" && ar x "$deb" && tar -xf data.tar.xz )
    # Termux installs to data/data/com.termux/files/usr/bin/proot
    src="$(find "$WORK" -path '*/bin/proot' -type f | head -1)"
    install -Dm755 "$src" "$JNILIBS/$abi/libproot.so"
    rm -rf "$WORK"/data* "$WORK/control.tar."* "$WORK/debian-binary" 2>/dev/null || true
done

echo "Done. proot binaries placed under $JNILIBS/*/libproot.so"
