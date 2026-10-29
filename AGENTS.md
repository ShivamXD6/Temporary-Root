# AGENTS.md

Android app that exploits kernel bugs to load a privileged session and hand root to a
KernelSU-compatible manager. Two paths:

- **DirtyFrag** (CVE-2026-43284): in-place AES-CBC ESP page-cache write, then a bundled LKM per KMI.
- **GhostLock** (CVE-2026-43499): futex PI use-after-free, driven by a per-kernel profile.

Java orchestration + native binaries + LKM (DirtyFrag only) + a vendored C++ core (GhostLock).

## Build

- `make` — full release: builds all DirtyFrag LKMs in podman DDK containers, runs `:app:assembleRelease`, copies to `out/TemporaryRoot.apk`. Requires exported `SIGNING_PASS` and `SIGNING_ALIAS`; `keystore.jks` / `signing.properties` are generated and gitignored. Needs bash + podman (Linux/WSL/macOS).
- `./gradlew :app:assembleDebug` — APK only. Works on Windows, but needs prebuilt `app/src/main/jni/ko/*.ko`; CMake `.incbin`s all eight and the build fails if any are missing. `make clean` deletes them.
- `./gradlew :app:assembleRelease` without `signing.properties` silently falls back to debug signing.
- JDK 21 (`gradle/gradle-daemon-jvm.properties`), compileSdk/targetSdk 37, minSdk 32, `arm64-v8a` only. Version `2.0`, `versionCode 5`.
- The app has no tests. `profile-core` has one (`ProfileCompilerTest`); run `:profile-core:test` after touching the compiler. Everything else is verified by `assemble` plus a device run.
- `make` derives `DDK_IMAGE`/`DDK_RELEASE` by parsing the `image:` line in `.github/workflows/build.yml` — update the workflow, not the Makefile, when bumping the DDK.
- CI (`.github/workflows/build.yml`) builds a signed release from secrets `KEYSTORE_BASE64` / `KEYSTORE_PASSWORD` / `KEY_ALIAS`, and falls back to debug signing when they are absent. Pushing a `v*` tag publishes a GitHub release.

## Architecture

- `MainActivity` owns the single page: strategy picker, check, run, console, manager download and install. `SettingsActivity` holds the toggles and the GhostLock profile import; `AboutActivity` the credits and donation links.
- `VulnerabilityStrategy` is the extension point. `MainActivity` registers `DirtyFragStrategy` and `GhostLockStrategy`; the picker, tips, check and run all read from the strategy, so a new path needs no UI change.
- DirtyFrag: `ExploitRunner` opens the IpSec transform, stages `libksud.so` and `libbootstrap.so`, execs `libtemproot.so` and streams stdout. `lkm/temproot.c` is compiled per-KMI, embedded into `exp.c`; `bootstrap.c` runs as root after `insmod`, applies RO / disable-modules / soft-reboot, then launches `ksud`.
- GhostLock: `GhostLockStrategy` resolves a profile (upstream list first, imported `.conf` second), `GhostLockProfile` compiles it to `filesDir/ghostlock/<uname -r>.glk1`, then execs `libghostlock.so` from `nativeLibraryDir`. The core calls `ksud` itself, so `GHOSTLOCK_KSUD` points it at a wrapper: `bootstrap --protect <ksud>`, the real `ksud "$@"`, then `bootstrap --reboot <ksud>` 6 s after a successful late-load.
- `libtemproot.so`, `libbootstrap.so` and `libghostlock.so` are executables renamed `.so` because AGP only packages `lib*.so`. They are exec'd from `nativeLibraryDir`, so `useLegacyPackaging` and `android:extractNativeLibs="true"` must stay enabled.
- `profile-core/` is a Kotlin module (parser, resolver, merger, exporter). `ProfileExporter`, `ProfileResolver` and `ProfileMerger` are reachable only through `ProfileCompiler`; the app calls `ProfileCompiler.compile` on every start.

## Kernel targets are duplicated (DirtyFrag only)

Adding or removing a KMI means editing all of:
`Makefile` (`KMIS`), `.github/workflows/build.yml` (matrix), `app/src/main/jni/CMakeLists.txt` (`KO_FILES`), `app/src/main/jni/exp.c` (`.incbin` asm block + `KoImage` table + externs), and `DirtyFragStrategy.SUPPORTED_LKMS`. The `temproot-<kmi>.ko` filename is the contract between them.

GhostLock needs no LKM. Its "target" is a profile file named `<uname -r>.conf`, and adding one is a data change, not a code change.

## Cross-language coupling that fails silently

- `applicationId` `build.bytes.temproot`, prefs file `temporary_root`, and keys `su_manager` / `soft_reboot` / `disable_modules` are hardcoded in `app/src/main/jni/bootstrap.c` under `/data/user_de/0/build.bytes.temproot/`. Renaming any of them breaks the root handoff with no build error.
- Java settings use device-protected storage (`createDeviceProtectedStorageContext()`); `bootstrap.c` reads that XML directly. Keep writes on the same context.
- GhostLock state is separate: `filesDir/ghostlock/` holds `<uname -r>.conf`, the compiled profile and `ksud.sh`. That is normal credential-encrypted storage, unlike the prefs.
- `/dev/tr` is the one-shot re-entry guard checked by `ExploitRunner` and `BootReceiver`. `/dev/trm*` files are debug breadcrumbs emitted by `bootstrap.c`, not functional state.

## Repo gotchas

- The package `build.bytes.temproot` lives under `app/src/main/java/build/`, which collides with Gradle's `build/` ignore rule. `.gitignore` only re-includes `*.java` directly in that folder, so new files or subpackages there are ignored by default.
- `mockups/` (design reference) and `lkm/nord2-4.19/` (`device.config` reference dump; non-GKI 4.19 is unsupported) are local only and untracked.
- `README.md` is maintained by the project owner.
- Never commit `signing.properties`, `keystore.jks`, or `local.properties`.
