# Architecture & design notes

## The core idea

Android apps run in an unprivileged sandbox: no root, no mounting, restricted
syscalls. `proot` works around this by intercepting syscalls in userspace via
`ptrace`, faking a chrooted filesystem and `root`-like UID. That lets a normal
APK unpack and run a real Ubuntu rootfs — `apt`, shared libs, services — without
touching the host system.

We deliberately keep the APK tiny and pull the OS at runtime:

1. **APK contents:** the Kotlin launcher, the `proot` binary (as a per-ABI
   native lib so the installer marks it executable), and four shell scripts.
2. **First launch:** `RootfsInstaller` (Kotlin) downloads an Ubuntu 22.04 rootfs
   from `cloud-images.ubuntu.com` (the *minimal* image for the lite flavor, the
   standard server rootfs otherwise), verifies its SHA256, and extracts it. This
   runs in-process because Android's sandbox has no `curl`/`tar`/`xz`.
3. **Desktop install:** `install-desktop.sh` (run inside the container) installs
   XFCE + TigerVNC + a curated tool set, then trims apt caches.
4. **Run:** `start-desktop.sh` launches XFCE on VNC display `:1`, reachable only
   on loopback (`127.0.0.1:5901`).

Everything that enters the container goes through `run-in-ubuntu.sh`, so the
proot bind flags (`/dev`, `/proc`, `/sys`, `/tmp`, rootfs, cwd, env) are defined
in exactly one place.

## Why XFCE and not ubuntu-desktop

`ubuntu-desktop` pulls GNOME plus systemd-managed services (NetworkManager,
gdm, snapd…) that simply can't run under proot — there's no PID 1, no real
init. XFCE is a self-contained X11 desktop that launches from a plain
`startxfce4` under `dbus-launch`, which is exactly what proot can host. It is
also far lighter, matching the "lightweight but complete" goal.

## Known limitations (honest list)

- **No real init/systemd.** Services that expect systemd won't start. Use the
  app's own foreground service for lifecycle, and start daemons manually.
- **proot has CPU overhead** from ptrace syscall interception — heavy compiles
  are slower than native. Fine for desktop use, editors, browsing, scripting.
- **Hardware access is limited** to what Android exposes to the sandbox (no raw
  GPU/USB). XFCE runs on a virtual framebuffer via VNC, not the GPU.
- **First run needs network** to fetch the rootfs and apt packages.

## Roadmap

- [x] **Embedded VNC viewer** — a self-written RFB client (`VncClient` +
      `VncCanvasView` + `VncActivity`) renders the desktop in-app with touch and
      soft-keyboard input. No external app or library.
- [ ] **Distro picker** — Debian / Alpine / Arch alongside Ubuntu.
- [ ] **Persistent sessions** — reconnect to a running desktop after the app is
      backgrounded.
- [ ] **Termux-X11 backend** as a faster alternative to VNC.
- [ ] **Resolution / DPI controls** in the UI.

## Build environment note

This project was scaffolded in a CI-style sandbox where the network policy
blocks `dl.google.com`, `packages.termux.dev` and Canonical's image host, and no
Android device was attached. As a result the APK is built and tested by the
developer locally. See Claude Code on the web network policies:
https://code.claude.com/docs/en/claude-code-on-the-web
