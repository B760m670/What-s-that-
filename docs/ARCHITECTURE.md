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

## The always-on service

A desktop is watched; a relay is not. The second case has different rules, and
they are easy to break by accident, so they are written down here.

**It is a separate container instance.** `service-run.sh` is entered through the
same `run-in-ubuntu.sh`, which means its own `proot`, its own tracer, its own
lifetime. It does not need a desktop, an X server or a session bus, and starting
or closing a desktop does not pass through it.

**It is exempt from the sweeps, by name of its tracer.** Closing a session
signals every ptrace tracee under the app's uid — that is the only criterion
that identifies the guest without a list of process names (see
`kill-session.sh`). The relay is a tracee like everything else, so it would be
swept away with the desktop. `service-run.sh` therefore records the pid of the
`proot` that traces it in `/tmp/wt-service/`, together with that process's own
pid and start time; `start-desktop.sh` and `kill-session.sh` read it, verify all
three (pids are recycled, and a stale file must not spare an unrelated process),
and skip that one tracer's tracees. Stopping the relay — `service-stop.sh` —
inverts the same criterion: those tracees and nothing else.

**Its output goes to a file, never to a pipe.** `/tmp/wt-service/log`, which is
app storage, so the app can read it without entering the container. If the relay
wrote to stdout and the app were later killed, the next write would take `EPIPE`
and `SIGPIPE` — a relay killed by the death of the process watching it is not
always-on. The supervisor appends its own notes to the same file; rotation
happens only in `service-run.sh`, at a start, when nothing holds the file.

**The supervisor adopts rather than duplicates.** `LinuxSessionService` restarts
the command when it exits, with a backoff that settles at a minute. But when it
is itself restarted — Android reclaiming the app, then `START_STICKY` — the
container is usually still running, so it checks the registration first and
watches the existing relay instead of starting a second copy.

**What Android still gets a say in.** Doze pauses the app's network for hours
without killing anything, so the UI asks for the battery exemption at the moment
the user switches the service on. `BootReceiver` brings it back after a reboot or
an app update, and nothing else: a desktop session is never restored unasked.

## Known limitations (honest list)

- **No real init/systemd.** Services that expect systemd won't start. Use the
  app's always-on service for anything that must keep running (above), and start
  daemons manually; D-Bus activation covers several of GNOME's own.
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
