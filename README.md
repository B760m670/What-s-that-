# What's that? — Linux

A **lightweight Android app (APK) that runs a full Ubuntu desktop (XFCE) inside
your phone** — no root required.

The APK itself stays small. On first launch it downloads a minimal Ubuntu base
image, unpacks it with [`proot`](https://proot-me.github.io/), installs a lean
XFCE desktop, and shows it over a loopback VNC connection. You get a real
`apt`-driven Ubuntu with windows, a terminal, a browser and dev tools — not a
toy emulator.

> Status: **v0.1 — working foundation, engine verified.** The proot + Ubuntu +
> `apt` engine was run end-to-end and confirmed working (see
> [`docs/ENGINE-VALIDATION.md`](docs/ENGINE-VALIDATION.md)). Building/signing the
> APK and testing the GUI on a device is the remaining step — the project was
> authored in a sandbox with no Android device and a network policy that blocks
> Google's SDK hosts, so that part runs on your machine.

## Compatibility & footprints

- **Runs from Android 5.0 (API 21) upward** — old and current phones alike.
- Two build flavors from one codebase:
  - **full** — XFCE + Firefox + dev tools, standard Ubuntu rootfs. Capable phones.
  - **lite** — Openbox + terminal + file manager, *minimal* Ubuntu rootfs.
    For old / low-RAM devices. Installs as a separate app (`.lite`).

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

## Get the APK (EAS Build, automatic on push)

Every push triggers an **EAS Build** that produces the installable APK
(`eas.json` + [`.eas/build/android-apk.yml`](.eas/build/android-apk.yml) +
[`.github/workflows/eas-build.yml`](.github/workflows/eas-build.yml)). It's a
**custom build** config (this is a native project, not React Native), so Gradle
runs `assembleFullDebug` / `assembleLiteDebug` from the repo root.

Download the finished APK from the build's page on your **Expo dashboard**
(expo.dev → your project → Builds), which gives a phone-friendly link / QR.

### One-time setup

1. Add an `EXPO_TOKEN` repo secret (Settings → Secrets → Actions). **Never
   commit the token.**
2. Link the Expo project so it has a `projectId`. The workflow attempts this
   automatically (`eas init --non-interactive --force`); if your account needs
   it done by hand, run once locally and commit:
   ```bash
   npx eas-cli@latest init   # writes extra.eas.projectId into app.json
   ```

The EAS job skips cleanly until the token is set, so it won't fail your pushes.

## Console

After the system is up, the app has an in-app **logs & command console**: type a
shell command, run it inside the container, and see stdout plus any error output
(and the exit code) in the log — handy for debugging without a separate terminal.

## Building locally

Prerequisites: **Android Studio** (or Android SDK cmdline-tools) + JDK 17.

```bash
# 1. Fetch the proot engine binaries (needs network to packages.termux.dev)
./tools/fetch-proot.sh

# 2. Point the build at your SDK
echo "sdk.dir=$HOME/Android/Sdk" > local.properties

# 3. Build the flavor you want
./gradlew assembleFullDebug   # full XFCE build
./gradlew assembleLiteDebug   # lightweight build for old phones
# → app/build/outputs/apk/<flavor>/debug/app-<flavor>-debug.apk
```

Install the APK, tap the button three times (Install Ubuntu → Install desktop →
Launch), and the desktop opens in the app's **built-in VNC viewer** — no
separate app needed. A floating ⌨ button shows the soft keyboard for typing.

## License & credits

Built on open source: `proot` (GPLv2), Ubuntu base images (Canonical),
TigerVNC, XFCE. This project's own code is MIT-licensed.

## Decentralised & secure

No central server, no account, no telemetry. The rootfs comes from public,
**user-swappable** mirrors and is **SHA256-verified** before install; the
desktop binds to loopback only. Full model in
[`docs/SECURITY.md`](docs/SECURITY.md).

## Docs

- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) — design, proot limits, roadmap
- [`docs/SECURITY.md`](docs/SECURITY.md) — decentralisation & security model
- [`docs/ENGINE-VALIDATION.md`](docs/ENGINE-VALIDATION.md) — proof the engine runs
