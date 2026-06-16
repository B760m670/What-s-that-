# Custom "What's that? — Linux" rootfs: Debian 12 + XFCE, pre-configured for
# proot/VNC. Built for arm64 in CI and shipped as a ready tarball, so the app
# never has to run apt on-device (no flaky mirrors, missing packages, etc.).
FROM debian:bookworm

ENV DEBIAN_FRONTEND=noninteractive

# Desktop + VNC + everyday tools, all baked in and tested here.
RUN apt-get update && apt-get install -y --no-install-recommends \
        xfce4 xfce4-terminal xfce4-goodies \
        dbus-x11 tigervnc-standalone-server tigervnc-common \
        fonts-dejavu-core procps \
        sudo nano less ca-certificates curl wget locales tzdata \
        firefox-esr mousepad ristretto \
        git python3 python3-pip \
    && sed -i 's/^# *en_US.UTF-8/en_US.UTF-8/' /etc/locale.gen && locale-gen \
    && apt-get clean && rm -rf /var/lib/apt/lists/*

# proot-friendly defaults baked in (so first launch needs no setup).
RUN rm -f /etc/resolv.conf \
    && printf 'nameserver 8.8.8.8\nnameserver 1.1.1.1\n' > /etc/resolv.conf \
    && printf '127.0.0.1 localhost\n' > /etc/hosts \
    && touch /root/.wt-desktop-ready
