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
  USB). GPU access goes through virgl — see below — and falls back to CPU
  rendering wherever that handshake fails.
- **First run needs network** to fetch the rootfs and apt packages.

## GPU acceleration (virgl)

The container is glibc; the phone's GL driver is a bionic blob under
`/vendor/lib*`. A glibc process can never `dlopen` it, so for a long time the
desktop rendered entirely on the CPU via Mesa's `llvmpipe`. Note this is *not*
a property of VNC or of X11 — swapping either for Wayland would not change it,
because the missing piece is a loadable driver, not a display protocol.

virgl splits the problem across the ABI boundary:

```
container (glibc)                        host (bionic, outside proot)
  GL app
    ↓
  Mesa, GALLIUM_DRIVER=virpipe
    ↓  serialised GL commands
  /tmp/.virgl_test  ────────────────→  libvirglserver.so
  (bound from $WT_HOME/tmp)              ↓
                                       system EGL/GLES → real GPU
```

- The server (`virgl_test_server_android`, from Termux's
  `virglrenderer-android`) is fetched by `tools/fetch-virgl.sh` and shipped as
  a native lib, the same trick already used for `proot`. `GpuBridge` launches
  and supervises it.
- The socket lives in the host dir that `run-in-ubuntu.sh` already binds to the
  guest's `/tmp`, so no extra plumbing is needed.
- Portable across vendors: it uses whatever GLES driver the device has, so it
  works on Mali as well as Adreno. (Turnip/Zink would be faster but is Adreno
  only.)

**Why there is a probe.** A live socket is not proof that GL works. If the
guest's Mesa and the server disagree on the vtest protocol version, the
connection is accepted and the client then *aborts* — Mesa does not fall back
to software by itself, so every GL app on the desktop would crash rather than
merely run slowly. `start-desktop.sh` therefore runs `glxinfo` against a
throwaway display first and only keeps `virpipe` if a renderer actually comes
back. This failure mode is not hypothetical: it was reproduced during
development by pairing Mesa 25.2 with virglrenderer 1.0.

proot's `ptrace` interception still adds latency to the socket traffic, so this
does not reach native speed — but the gap between `llvmpipe` and a real GPU is
much larger than that overhead.

## Roadmap

- [x] **Embedded VNC viewer** — a self-written RFB client (`VncClient` +
      `VncCanvasView` + `VncActivity`) renders the desktop in-app with touch and
      soft-keyboard input. No external app or library.
- [ ] **Distro picker** — Debian / Alpine / Arch alongside Ubuntu.
- [ ] **Persistent sessions** — reconnect to a running desktop after the app is
      backgrounded.
- [x] **GPU passthrough via virgl** — hardware GL for the container, with an
      automatic probe and a software fallback. Needs on-device confirmation.
- [ ] **Shared-framebuffer backend** as a faster alternative to VNC — the other
      half of the graphics work: virgl fixed *who renders*, this fixes *how the
      pixels reach the screen*. Today a frame is rendered on the GPU, read back
      to CPU memory, encoded by Xvnc, sent over a socket, decoded in Kotlin and
      uploaded to the GPU again. The plan is `Xvfb -fbdir`, whose framebuffer is
      an mmap-able file in the already-bound `/tmp`, read directly and uploaded
      as a texture (the B,G,R,X byte order costs nothing to swizzle in a shader).
      - [x] Input half: `XTestInput`, an X11/XTEST client, since a framebuffer
            carries no input channel the way RFB does.
      - [ ] Display half: mmap the framebuffer and render it.
      - Note: Termux-X11 (Lorie) solves this with a zero-copy `AHardwareBuffer`
        and would be strictly better, but it is GPLv3 and this project is MIT,
        so embedding it would relicense the app.
- [ ] **Resolution / DPI controls** in the UI.

## Build environment note

This project was scaffolded in a CI-style sandbox where the network policy
blocks `dl.google.com`, `packages.termux.dev` and Canonical's image host, and no
Android device was attached. As a result the APK is built and tested by the
developer locally. See Claude Code on the web network policies:
https://code.claude.com/docs/en/claude-code-on-the-web
