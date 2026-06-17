# Custom "Windows apps (Wine)" rootfs: Debian + light Openbox desktop + Box64
# (x86-64 -> ARM64) + Wine, all baked in & built in CI. The app downloads this
# ready image — no on-device apt/box64/wine install, no upstream timeouts.
FROM debian:bookworm

ENV DEBIAN_FRONTEND=noninteractive

# Light desktop to host Wine windows + tools.
RUN apt-get update && apt-get install -y --no-install-recommends \
        openbox tint2 xterm pcmanfm \
        dbus-x11 tigervnc-standalone-server tigervnc-common \
        fonts-dejavu-core procps \
        sudo nano less ca-certificates curl wget gnupg xz-utils cabextract \
    && apt-get clean && rm -rf /var/lib/apt/lists/*

# Box64: x86-64 -> ARM64 translation (installed only; it runs on the device).
RUN wget -qO- https://ryanfortner.github.io/box64-debs/KEY.gpg \
        | gpg --dearmor -o /usr/share/keyrings/box64-debs-archive-keyring.gpg \
    && echo "deb [signed-by=/usr/share/keyrings/box64-debs-archive-keyring.gpg] https://ryanfortner.github.io/box64-debs/ ./" \
        > /etc/apt/sources.list.d/box64.list \
    && apt-get update \
    && ( apt-get install -y box64-generic-arm || apt-get install -y box64-android || apt-get install -y box64 ) \
    && apt-get clean && rm -rf /var/lib/apt/lists/*

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

RUN touch /root/.wt-desktop-ready
