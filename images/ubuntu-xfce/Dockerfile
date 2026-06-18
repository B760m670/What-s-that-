# Custom "What's that? — Linux" rootfs: Ubuntu 22.04 + XFCE, pre-configured for
# proot/VNC. Built for arm64 in CI and shipped as a ready tarball, so the app
# never runs apt on-device (no flaky mirrors, no dpkg-under-proot failures).
FROM ubuntu:22.04

ENV DEBIAN_FRONTEND=noninteractive

# Desktop + VNC + everyday tools, all baked in and tested here. (No browser:
# Ubuntu ships Firefox only as a snap, which can't run under proot — leave it
# out so the image stays clean and reliable; users can add one inside.)
RUN apt-get update && apt-get install -y --no-install-recommends \
        xfce4 xfce4-terminal xfce4-goodies \
        dbus-x11 tigervnc-standalone-server tigervnc-common \
        fonts-dejavu-core procps \
        sudo nano less ca-certificates curl wget locales tzdata \
        mousepad ristretto \
        git python3 python3-pip \
    && sed -i 's/^# *en_US.UTF-8/en_US.UTF-8/' /etc/locale.gen && locale-gen \
    && apt-get clean && rm -rf /var/lib/apt/lists/*

# Mark the desktop as ready (DNS/hosts are written by the app at install time).
RUN touch /root/.wt-desktop-ready
