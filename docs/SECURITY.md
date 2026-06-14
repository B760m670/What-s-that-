# Security & decentralisation model

A core design goal: **no central dependency, nothing to trust but you.** The app
is not a thin client for someone's server — it assembles a local Linux from
public, verifiable sources and runs it entirely on your device.

## Decentralised by design

- **No central app server.** The app never calls home. There is no account, no
  backend, no telemetry, no analytics. Removing the developer from the picture
  entirely does not stop it from working.
- **Sources are public mirrors, and swappable.** The Ubuntu rootfs comes from
  public Ubuntu image mirrors. You can redirect to *any* mirror you trust — a
  geographically closer one, a corporate mirror, or your own self-hosted copy:
  - `WT_ROOTFS_MIRRORS` — space-separated mirror base URLs, tried in order.
  - `WT_ROOTFS_URL` — an exact tarball URL, overriding everything.
- **The engine is open and reusable.** `proot` (GPLv2) and the rootfs are
  standard components; nothing proprietary is wedged in between.
- **Builds are reproducible and forkable.** The entire build is defined in-repo
  (`eas.json`, `.eas/build/android-apk.yml`); anyone can fork and rebuild from
  source. The APK is currently produced via EAS Build (Expo's cloud) — a
  convenience that does introduce a third-party builder into the supply chain.
  If you want a fully self-hosted, no-third-party pipeline, swap the EAS
  workflow for a self-hosted GitHub Actions runner (or any CI) that runs
  `./gradlew assembleFullDebug` — the Gradle build needs nothing Expo-specific.

## Security measures

- **Integrity-verified downloads.** Every rootfs download is checked against the
  publisher's `SHA256SUMS` before extraction. A mismatch aborts the install
  (verified working — see `ENGINE-VALIDATION.md`). Tampered or corrupt images
  are refused.
- **Loopback-only desktop.** The VNC server binds to `127.0.0.1` only; the
  desktop is never exposed on the network.
- **Unprivileged by default.** Runs via `proot` with no root and no `setuid`.
  It cannot touch other apps or the Android system — the OS sandbox still holds.
- **No secret collection.** The command console runs locally inside the
  container; input and output stay on the device.

## What you should still know

- `proot` fakes `root` *inside the container only*. It is isolation for
  convenience, not a security boundary against the Android OS (that boundary is
  Android's own app sandbox, which remains in force).
- Installing software with `apt` trusts Ubuntu's archive keys, exactly like a
  desktop Ubuntu. Add third-party repos at your own risk.
- HTTPS + checksums protect the rootfs download; pin your own mirror if you want
  to remove even the upstream from your trust set.
