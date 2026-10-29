# Changelog

All notable changes to Temporary Root. Version `2.0` is `versionCode 5`.

The project restarted versioning at 2.0 with the GhostLock rewrite; git tags
`v3.0`-`v4.5` belong to the earlier DirtyFrag-only lineage and predate it.

## [2.0] - 2026-10-29

### Added

- GhostLock path (CVE-2026-43499): `libghostlock.so` core, on-device profile compiler,
  upstream profile list cached for 24 h, retry on transient GitHub API failures.
- GhostLock profile import from Settings, for kernels upstream does not cover.
- Live ESP/CBC probe in the device check, so "Vulnerable" is never guessed.
- Success estimate per run: base set by profile source (50% upstream, 5% imported) and
  adjusted for RAM, thermal, uptime, battery, battery saver, doze, ksud, unfinished runs
  and quiet hours.
- Start prompt at 85% or better instead of starting automatically.
- `estimate: N%` line in the run log.
- Manager download in the app, with a build picker for releases that ship both spoofed
  and ABI builds; an already-downloaded APK is reused.
- About screen with credits (DFRoot by diabl0w) and donation links.
- Signed release builds from CI secrets; pushing a `v*` tag publishes a GitHub release.

### Changed

- Manager launches 5 s after root is up.
- RO protection, disable-modules and soft reboot now apply to GhostLock too, through a
  `bootstrap --protect` / `--reboot` handoff around `ksud late-load`.
- Auto soft reboot is off by default; "Launch manager after success" is disabled while it is on.
- Download picker only appears when both build kinds exist; otherwise the right APK is
  downloaded directly and the system installer opens automatically.
- Check is passive: it no longer expands the console or scrolls the page.
- The estimate no longer uses CPU load, which is not readable without root.
- Single-page layout, AMOLED dark theme, per-exploit tips card.
- On Samsung, only the diabl0w KernelSU fork is listed.

### Fixed

- Imported profiles were read from credential-encrypted storage, so a reboot dropped them.
- Downloading could crash when reusing an already-downloaded APK.
- A failed run no longer leaves the app claiming "Temporary Root Active": the status
  now needs a working `su`, and a second Start reports that a reboot is required instead
  of reporting success without doing anything.
- When the kernel module fails to load, the console now prints insmod's own error
  (or says the hook could not start `/vendor/bin/insmod`), instead of only `timeout`.

### Removed

- Boot image analyzer.
- Tabs, bottom navigation and swipe navigation.

## [1.0] - 2026-09-25

First release: ephemeral root for devices vulnerable to DirtyFrag (CVE-2026-43284).

- AES-CBC ESP page-cache write, 16 bytes per block, no write permission and no copy-on-write.
- Bundled `temproot` LKM for all eight GKI KMIs, android12-5.10 through android17-6.18.
- `libc` patch to run `insmod` from the exploit process.
- Hands root to the KernelSU manager that ships `libksud.so`; no Shizuku and no Wi-Fi.
- Start on boot.
- Automatic soft reboot, inhibited by holding volume down when bootlooping.
- Read-only partition protection.
- Hides SELinux modifications from the manager.
