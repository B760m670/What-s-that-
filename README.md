# What's that? — Linux

A **lightweight Android app (APK) that runs a full Ubuntu desktop (XFCE) inside
your phone** — no root required.

The APK itself stays small. On first launch it downloads a minimal Ubuntu base
image, unpacks it with [`proot`](https://proot-me.github.io/), installs a lean
XFCE desktop, and shows it over a loopback VNC connection. You get a real
`apt`-driven Ubuntu with windows, a terminal, a browser and dev tools — not a
toy emulator.

> Status: **v0.1 — working foundation.** The Android project, the proot engine
> integration and the Ubuntu/XFCE bootstrap scripts are complete and reviewed.
> Building the signed APK and testing on a device is done by you (see below):
> the project was authored in a sandbox with no Android device and a restricted
> network, so an on-device run is the remaining step.

## Why "download Ubuntu as an APK" works this way

Bundling a 1.5–4 GB rootfs *inside* the APK would make it huge and unshippable.
So, like the open-source UserLAnd and Andronix, this app ships a **small
launcher** and fetches the OS image on first run. Light APK, full system inside.

```
┌──────────────────────── Android phone ─────────────────────────┐
│  What's that? — Linux (APK)                                     │
│    MainActivity ── drives ──► LinuxEnvironment (Kotlin engine)   │
│                                   │ runs                         │
│                                   ▼                              │
│            proot (no-root container)                            │
│                                   │                              │
│                                   ▼                              │
│            Ubuntu 22.04 rootfs ── XFCE ── TigerVNC ──► VNC view  │
└─────────────────────────────────────────────────────────────────┘
```

## Project layout

| Path | What it is |
|------|------------|
| `app/src/main/java/.../LinuxEnvironment.kt` | Engine: arch detection, asset prep, runs the scripts through proot |
| `app/src/main/java/.../MainActivity.kt` | One-button UI: Install Ubuntu → Install XFCE → Launch |
| `app/src/main/java/.../LinuxSessionService.kt` | Foreground service keeping the session alive |
| `app/src/main/assets/scripts/bootstrap.sh` | Downloads + extracts the Ubuntu rootfs |
| `app/src/main/assets/scripts/run-in-ubuntu.sh` | The single proot entry point (all binds in one place) |
| `app/src/main/assets/scripts/install-desktop.sh` | Installs the lean XFCE desktop + tools |
| `app/src/main/assets/scripts/start-desktop.sh` | Starts XFCE on a VNC display |
| `tools/fetch-proot.sh` | Pulls prebuilt `proot` binaries into `jniLibs/` |

## Building the APK

Prerequisites: **Android Studio** (or Android SDK cmdline-tools) + JDK 17.

```bash
# 1. Fetch the proot engine binaries (needs network to packages.termux.dev)
./tools/fetch-proot.sh

# 2. Point the build at your SDK
echo "sdk.dir=$HOME/Android/Sdk" > local.properties

# 3. Build
./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

Install the APK, tap the button three times (Install Ubuntu → Install XFCE →
Launch), and connect with any VNC viewer to `127.0.0.1:5901` (an embedded
viewer is the next milestone — see `docs/ARCHITECTURE.md`).

## License & credits

Built on open source: `proot` (GPLv2), Ubuntu base images (Canonical),
TigerVNC, XFCE. This project's own code is MIT-licensed.

See [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for the design, the known
limitations of running under proot, and the roadmap.
