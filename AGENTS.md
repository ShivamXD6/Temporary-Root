# AGENTS.md

Android app that exploits CVE-2026-43284 (DirtyFrag) to load a bundled kernel module and hand root to a KernelSU-compatible manager. Java orchestration + native exploit binaries + a Linux kernel module.

## Build

- `make` — full release: builds all LKMs in podman DDK containers, runs `:app:assembleRelease`, copies to `out/TemporaryRoot.apk`. Requires exported `SIGNING_PASS` and `SIGNING_ALIAS`; `keystore.jks` / `signing.properties` are generated and gitignored. Needs bash + podman (Linux/WSL/macOS).
- `./gradlew :app:assembleDebug` — APK only (what CI builds). Works on Windows, but needs prebuilt `app/src/main/jni/ko/*.ko`; CMake `.incbin`s all eight and the build fails if any are missing. `make clean` deletes them.
- `./gradlew :app:assembleRelease` without `signing.properties` silently falls back to debug signing (`app/build.gradle.kts`).
- JDK 21 (`gradle/gradle-daemon-jvm.properties`), compileSdk/targetSdk 37, minSdk 32, `arm64-v8a` only.
- There is no test suite. A successful `assemble` (plus a device run) is the only verification.
- `make` derives `DDK_IMAGE`/`DDK_RELEASE` by parsing the `image:` line in `.github/workflows/build.yml` — update the workflow, not the Makefile, when bumping the DDK.

## Kernel targets are duplicated

Adding or removing a KMI means editing all of:
`Makefile` (`KMIS`), `.github/workflows/build.yml` (matrix), `app/src/main/jni/CMakeLists.txt` (`KO_FILES`), `app/src/main/jni/exp.c` (`.incbin` asm block + `KoImage` table + externs), and `DirtyFragStrategy.SUPPORTED_LKMS`. The `temproot-<kmi>.ko` filename is the contract between them.

## Architecture

- `ExploitRunner` opens the IpSec transform, stages `libksud.so` and `libbootstrap.so` from the manager's native lib dir into app data, then execs `libtemproot.so` and streams its stdout.
- `libtemproot.so` and `libbootstrap.so` are freestanding executables renamed `.so` because AGP only packages `lib*.so`. They are exec'd from `nativeLibraryDir`, so `useLegacyPackaging` and `android:extractNativeLibs="true"` must stay enabled.
- `lkm/temproot.c` is compiled per-KMI and embedded into `exp.c`; `bootstrap.c` runs as root after `insmod` and launches `ksud`.
- `VulnerabilityStrategy` is the extension point for new research paths, but `MainActivity` only registers `DirtyFragStrategy`; KO selection and packaging are DirtyFrag-specific.

## Cross-language coupling that fails silently

- `applicationId` `build.bytes.temproot`, prefs file `temporary_root`, and keys `su_manager` / `soft_reboot` / `disable_modules` are hardcoded in `app/src/main/jni/bootstrap.c` under `/data/user_de/0/build.bytes.temproot/`. Renaming any of them breaks the root handoff with no build error.
- Java settings use device-protected storage (`createDeviceProtectedStorageContext()`); `bootstrap.c` reads that XML directly. Keep writes on the same context.
- `/dev/tr` is the one-shot re-entry guard checked by `ExploitRunner` and `BootReceiver`. `/dev/trm*` files are debug breadcrumbs emitted by `bootstrap.c`, not functional state.

## Repo gotchas

- The package `build.bytes.temproot` lives under `app/src/main/java/build/`, which collides with Gradle's `build/` ignore rule. `.gitignore` only re-includes `*.java` directly in that folder, so new files or subpackages there are ignored by default.
- `mockups/` is design reference only. `lkm/nord2-4.19/device.config` is a reference config dump, not a build target (non-GKI 4.19 is unsupported).
- Never commit `signing.properties`, `keystore.jks`, or `local.properties`.
