# Custom "Windows apps (Wine)" rootfs: Debian + light Openbox desktop + Box64
# (x86-64 -> ARM64) + Wine, all baked in & built in CI. The app downloads this
# ready image — no on-device apt/box64/wine install. EXPERIMENTAL: on a Mali GPU
# only software rendering is available, so expect simple/old programs, not games.

# --- Stage 1: cross-compile Box64 on the NATIVE build host (fast). -----------
# Building Box64 inside the arm64 image would run under QEMU and take 40+ min.
# Here we build on the runner's native x86-64 with an aarch64 cross-compiler
# (~2 min) and copy the finished arm64 binary into the real image.
FROM --platform=$BUILDPLATFORM debian:bookworm AS box64
ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends \
        git ca-certificates cmake make python3 \
        gcc-aarch64-linux-gnu g++-aarch64-linux-gnu \
    && rm -rf /var/lib/apt/lists/*
RUN git clone --depth 1 https://github.com/ptitSeb/box64 /tmp/box64 \
    && cmake -S /tmp/box64 -B /tmp/box64/build \
        -DARM_DYNAREC=ON -DCMAKE_BUILD_TYPE=RelWithDebInfo \
        -DCMAKE_SYSTEM_NAME=Linux -DCMAKE_SYSTEM_PROCESSOR=aarch64 \
        -DCMAKE_C_COMPILER=aarch64-linux-gnu-gcc \
        -DCMAKE_CXX_COMPILER=aarch64-linux-gnu-g++ \
    && cmake --build /tmp/box64/build -j"$(nproc)"

# --- Stage 2: the actual arm64 rootfs. ---------------------------------------
FROM debian:bookworm
ENV DEBIAN_FRONTEND=noninteractive

# Light desktop to host Wine windows + tools.
RUN apt-get update && apt-get install -y --no-install-recommends \
        openbox tint2 xterm pcmanfm \
        dbus-x11 tigervnc-standalone-server tigervnc-common \
        fonts-dejavu-core procps \
        sudo nano less ca-certificates curl wget gnupg xz-utils cabextract \
    && apt-get clean && rm -rf /var/lib/apt/lists/*

# Drop in the cross-compiled Box64 (arm64) binary.
COPY --from=box64 /tmp/box64/build/box64 /usr/local/bin/box64
RUN chmod +x /usr/local/bin/box64

# Wine (x86-64 build, executed via Box64 on the device).
RUN mkdir -p /opt/wine \
    && wget -O /tmp/wine.tar.xz \
        https://github.com/Kron4ek/Wine-Builds/releases/download/9.0/wine-9.0-amd64.tar.xz \
    && tar -xf /tmp/wine.tar.xz -C /opt/wine --strip-components=1 \
    && rm -f /tmp/wine.tar.xz

# `wine` / `wine64` wrappers that run the x86-64 Wine through Box64.
RUN printf '#!/bin/sh\nexport WINEPREFIX="${WINEPREFIX:-/root/.wine}"\nexec box64 /opt/wine/bin/wine64 "$@"\n' \
        > /usr/local/bin/wine \
    && cp /usr/local/bin/wine /usr/local/bin/wine64 \
    && chmod +x /usr/local/bin/wine /usr/local/bin/wine64

# Mark the desktop as ready so the app launches it directly (no on-device apt).
RUN touch /root/.wt-desktop-ready
