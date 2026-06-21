# Custom "Windows apps (Wine)" rootfs: Debian + XFCE desktop + a browser +
# Box64 (x86-64 -> ARM64) + Wine, all baked in & built in CI. Gives a real
# desktop (menu, panel, file manager) that also runs Windows .exe via Wine.
# EXPERIMENTAL: on a Mali GPU only software rendering — simple/old programs,
# not modern games.

# --- Stage 1: cross-compile Box64 on the NATIVE build host (fast). -----------
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

# A real XFCE desktop (menu, panel, file manager) + a browser + tools, so this
# isn't a bare window manager but something usable that also hosts Wine.
RUN apt-get update && apt-get install -y --no-install-recommends \
        xfce4 xfce4-terminal xfce4-goodies thunar \
        dbus-x11 tigervnc-standalone-server tigervnc-common \
        pulseaudio pulseaudio-utils \
        fonts-dejavu-core procps \
        sudo nano less ca-certificates curl wget gnupg xz-utils cabextract zenity \
        firefox-esr mousepad \
        locales tzdata \
    && sed -i 's/^# *en_US.UTF-8/en_US.UTF-8/' /etc/locale.gen && locale-gen \
    && apt-get clean && rm -rf /var/lib/apt/lists/*

# Drop in the cross-compiled Box64 (arm64) binary.
COPY --from=box64 /tmp/box64/build/box64 /usr/local/bin/box64
RUN chmod +x /usr/local/bin/box64

# x86_64 runtime libs that Box64 needs to load Wine's emulated .so files — e.g.
# Wine's ntdll.so links libgcc_s.so.1, which must exist as x86_64 (rootfs is arm64).
RUN dpkg --add-architecture amd64 \
    && apt-get update \
    && apt-get install -y --no-install-recommends \
        libc6:amd64 libgcc-s1:amd64 libstdc++6:amd64 \
    && apt-get clean && rm -rf /var/lib/apt/lists/*

# Wine (x86-64 build, executed via Box64 on the device).
RUN mkdir -p /opt/wine \
    && wget -O /tmp/wine.tar.xz \
        https://github.com/Kron4ek/Wine-Builds/releases/download/9.0/wine-9.0-amd64.tar.xz \
    && tar -xf /tmp/wine.tar.xz -C /opt/wine --strip-components=1 \
    && rm -f /tmp/wine.tar.xz

# `wine` / `wine64` wrappers that run the x86-64 Wine through Box64, with the
# x86_64 lib dirs on Box64's search path.
RUN printf '#!/bin/sh\nexport WINEPREFIX="${WINEPREFIX:-/root/.wine}"\nexport BOX64_LD_LIBRARY_PATH="/usr/lib/x86_64-linux-gnu:/lib/x86_64-linux-gnu${BOX64_LD_LIBRARY_PATH:+:$BOX64_LD_LIBRARY_PATH}"\nexec box64 /opt/wine/bin/wine64 "$@"\n' \
        > /usr/local/bin/wine \
    && cp /usr/local/bin/wine /usr/local/bin/wine64 \
    && chmod +x /usr/local/bin/wine /usr/local/bin/wine64

# A friendly "Run Windows program (.exe)" launcher: pick a file, run it in Wine.
# Shows up in the XFCE applications menu. (Installed Windows apps also add their
# own menu entries automatically.)
RUN printf '#!/bin/sh\nf="$(zenity --file-selection --title="Choose a Windows .exe" 2>/dev/null)" || exit 0\n[ -n "$f" ] && exec wine "$f"\n' \
        > /usr/local/bin/run-exe \
    && chmod +x /usr/local/bin/run-exe \
    && mkdir -p /usr/share/applications \
    && printf '[Desktop Entry]\nType=Application\nName=Run Windows program (.exe)\nExec=run-exe\nIcon=wine\nTerminal=false\nCategories=Wine;Utility;\n' \
        > /usr/share/applications/run-windows-exe.desktop

# Mark the desktop as ready so the app launches it directly (no on-device apt).
RUN touch /root/.wt-desktop-ready
