# Changelog

All notable changes to Temporary Root.

---

## Temporary Root 2.0

### Added

#### GhostLock — [CVE-2026-43499](https://github.com/advisories/GHSA-cqc6-9f34-295v)
- Added a second exploit path based on a Futex PI use-after-free vulnerability.
- Added kernel-specific profiles requiring an exact `uname -r` match.
- Profiles are fetched from upstream, cached for 24 hours, and compiled on-device.
- Added **Settings → Import GhostLock Profile** to load custom `.conf` profiles.
- Upstream profiles take priority; imported profiles are used only when no upstream profile exists.
- Missing profiles are reported explicitly instead of assuming compatibility.

#### Compatibility Checks & Success Estimates
- Added three-stage validation: kernel branch, module signing, and a live 16-byte write probe through the ESP/CBC path.
- Compatibility results now control the status indicator and Start button.
- Added dynamic success estimates based on profile source and device conditions, including RAM, thermal state, uptime, battery, battery saver, Doze, ksud, and unfinished runs.
- At estimates of 85% or higher, the app asks for confirmation instead of starting automatically.
- Every run records its estimated success probability alongside the outcome.

#### Root Manager Integration
- Added OriginSU as the default manager, alongside KernelSU, KernelSU-Next, ReSukiSU, and SukiSU-Ultra.
- Restricted Samsung manager selection to the supported diabl0w KernelSU fork.
- Added in-app APK downloads with build selection (spoofed/ABI), reuse of existing downloads, and automatic launch of the system installer.

#### Settings & UI
- Added a Material 3 interface with a single-page layout and AMOLED dark theme enabled by default.
- Added a collapsible console, exploit-specific tips, and an About section with credits and donation links.
- Added read-only partition protection: mounts/marks critical partitions (`super`, `misc`, `steady`, and slot partitions `*_a`/`*_b`) as read-only (`BLKROSET`) to prevent bricking.
- Added KSU module disabling and automatic soft reboot controls.
- Automatic soft reboot is disabled by default. The manager auto-launch option is unavailable while it is enabled.
- Added a minimum five-second delay after root acquisition before launching the manager.

### Fixed
- Display the actual `insmod` error when kernel module loading fails instead of showing only a timeout.
- Prevent failed runs from leaving the app in the Temporary Root Active state.
- Correctly report when a reboot is required after a hook instead of incorrectly reporting success.
- Persist imported GhostLock profiles across reboots using device-protected storage.
- Fixed crashes when downloading an APK that has already been downloaded.

### Installation
- Download `TemporaryRoot_2.0.apk` from the releases section.
- If upgrading from a v1.x build signed with a different key, uninstall the previous version first.
- Select your root manager, run the compatibility check, and press Start.

### Known Limitations
- **Non-GKI 4.19:** Unsupported due to the lack of a matching LKM and GhostLock profile.
- **Samsung:** Only the diabl0w KernelSU fork is supported. DEFEX and Knox behaviour varies by build.
- **Zygisk / LSPosed:** Modules load during the soft reboot triggered after late-loading. They are not active during the initial seconds of temporary root.

---

## Temporary Root 1.0

Ephemeral kernel root for devices vulnerable to DirtyFrag ([CVE-2026-43284](https://github.com/advisories/GHSA-mmw8-mxmc-8w2r)). Nothing is written to your boot image — reboot and the device is stock again.

### Features
- 16-byte page-cache write through the real ESP/CBC path
- Bundled temproot LKM for eight GKI branches, Android 12 (5.10) through Android 17 (6.18)
- Hands root to any KernelSU-compatible manager that ships `libksud.so`
- Start on boot
- Automatic soft reboot, inhibited by holding volume down if the device bootloops
- Read-only partition protection: mounts critical partitions (`super`, `misc`, `steady`, and `*_a`/`*_b`) as read-only (`BLKROSET`) to prevent bricking
- Hides SELinux modifications from the manager
- No Shizuku, no Wi-Fi, no boot image patching

### Notes
- Temporary by design: root, modules and manager state all disappear on reboot
- Modules work, including Zygisk and LSPosed — they need the soft reboot so zygote restarts with the module loaded, no full reboot required

**Full Changelog:** https://github.com/ShivamXD6/Temporary-Root/commits/v1.0
