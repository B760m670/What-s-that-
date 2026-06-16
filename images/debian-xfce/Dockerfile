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

# Mark the desktop as ready. DNS/hosts are written by the app at install time;
# /etc/resolv.conf is a Docker-managed bind-mount during build, so don't touch it.
RUN touch /root/.wt-desktop-ready
