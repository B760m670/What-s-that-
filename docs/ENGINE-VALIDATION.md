# Engine validation

The Android packaging needs a device + the Google-hosted SDK to test. But the
*engine* underneath — proot running an Ubuntu rootfs with a working `apt` — is
plain Linux and was validated directly on an x86_64 machine using the exact
scripts shipped in `app/src/main/assets/scripts/`.

## What was run

Same env vars the app exports (`WT_HOME`, `WT_ARCH`, `PROOT`, `WT_VARIANT`,
`WT_PROFILE`), with the system `proot` standing in for the Termux build:

```
$ sh scripts/bootstrap.sh            # download + extract Ubuntu minimal rootfs
[bootstrap] Extracting rootfs (this runs once)...
[bootstrap] Ubuntu rootfs ready at /tmp/wt-test/ubuntu

$ sh scripts/run-in-ubuntu.sh /bin/bash -c '...'
OS: Ubuntu 22.04.5 LTS
uname: Linux x86_64
whoami: root  cwd: /root  HOME: /root
WT_PROFILE=full  WT_GEOMETRY=1280x720
dpkg knows 380 packages
apt present: /usr/bin/apt-get

$ # inside the container:
$ apt-get update -y
Fetched 48.1 MB in 19s (2474 kB/s)
$ apt-get install -y --no-install-recommends tree
Setting up tree (2.0.2-1) ...
$ tree --version
tree v2.0.2 ...
```

## What this proves

- The Ubuntu rootfs from `cloud-images.ubuntu.com` unpacks and boots under proot.
- The single `run-in-ubuntu.sh` entry point enters the guest with the right
  root id, working dir, binds and forwarded `WT_*` env.
- DNS + `apt` work inside the guest: package lists update and packages install
  and run. `install-desktop.sh` is the same mechanism with more packages.

## Bugs found and fixed during validation

1. Cloud images ship `/etc/resolv.conf` as a **dangling symlink** into
   systemd-resolved — writing DNS config failed. Fixed: `rm -f` before writing.
2. `run-in-ubuntu.sh` originally used Termux-only proot flags
   (`--kill-on-exit`, `--cwd`, `--link2symlink`). Switched to the portable
   subset (`-0 -r -w -b`) so the same wrapper runs on-device and in tests.
   Dropping `--kill-on-exit` also fixes a real bug: it would have killed the
   background VNC server when `start-desktop.sh` returned.

## Not yet validated on-device

- Building/signing the APK (needs Android SDK; blocked host in this sandbox).
- The XFCE/Openbox GUI over VNC (needs a display + device).
- The Termux-built `proot` binary specifically (the portable flag subset is
  a superset-safe choice, so behaviour should match).
