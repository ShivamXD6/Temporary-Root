<!--suppress HtmlDeprecatedAttribute -->
<div align="center">
  <img src=".github/icon.png" alt="Temporary Root Icon" width="128"/>
  <h1>⚡Temporary Root</h1>
  <p><b>Kernel-level temporary root that hands over to your KernelSU manager.</b></p>

[![Downloads](https://img.shields.io/github/downloads/ShivamXD6/Temporary-Root/total?color=green&style=for-the-badge)](../../releases)
[![Release](https://img.shields.io/github/v/release/ShivamXD6/Temporary-Root?style=for-the-badge)](../../releases)
[![Join Build Bytes](https://img.shields.io/badge/Join-Build%20Bytes-2CA5E0?style=for-the-badge&logo=telegram)](https://telegram.me/BuildBytes)
[![Join Chat](https://img.shields.io/badge/Join%20Chat-Build%20Bytes%20Discussion-2CA5E0?style=for-the-badge&logo=telegram)](https://telegram.me/BuildBytesDiscussion)
![Android](https://img.shields.io/badge/Android-3DDC84?style=for-the-badge&logo=android&logoColor=white)
![Root](https://img.shields.io/badge/Root-ff0000?style=for-the-badge&logo=superuser&logoColor=white)
![KernelSU](https://img.shields.io/badge/KernelSU-000000?style=for-the-badge&logo=linux&logoColor=white)

</div>

<br>

- **Temporary Root** gains a privileged session by exploiting a kernel vulnerability, loads a
  KernelSU-compatible module, and hands root to the manager you already use. Reboot and the device
  is back to stock.

---

## 📑 Table of Contents

- [💡 Why Temporary Root?](#-why-temporary-root)
- [✨ Features](#-features)
  - [🧪 Compatibility Check](#-compatibility-check)
  - [🧨 DirtyFrag](#-dirtyfrag)
  - [👻 GhostLock](#-ghostlock)
  - [🧩 Root Managers](#-root-managers)
  - [⚙️ Settings](#️-settings)
- [📥 Requirements & Installation](#-requirements--installation)
- [🔬 How It Works](#-how-it-works)
- [🛠️ Building](#️-building)
- [🔑 Signing](#-signing)
- [❓ FAQ](#-faq)
- [💖 Support the Project](#-support-the-project)
- [🙏 Credits](#-credits)

---

## 💡 Why Temporary Root?

- Most root methods ask you to unlock, patch and flash a boot image, which is permanent, device
  specific and easy to get wrong. Temporary Root does the opposite: it stays inside a running
  Android system, uses a kernel bug for a few seconds, and gives the result to a normal KernelSU
  manager.

- It also solves the boring part — knowing whether your kernel is actually exploitable. The app runs
  a real probe instead of guessing from a version string, so "compatible" means tested, not assumed.

---

## ✨ Features

### 🧪 Compatibility Check

_Three gates, and the last one is a live test._

<details>
<summary><b>How the check decides (Click to Expand)</b></summary>

- Kernel branch gate — DirtyFrag only has modules for the GKI branches it ships (5.10, 5.15, 6.1,
  6.6, 6.12, 6.18). Non-GKI 4.x kernels are rejected with an explanation instead of a false positive.
- Module signing gate — if the kernel sets `CONFIG_MODULE_SIG_FORCE=y`, the bundled unsigned modules
  cannot load, so the check stops there.
- Live probe — writes 16 bytes into a private temp file's page cache through the real ESP/CBC
  primitive and reads them back:
  - pattern changed → **Vulnerable**
  - unchanged → **Not vulnerable (patched)**
  - probe could not run → **Check inconclusive**, never a false "compatible"
- The verdict drives the status dot and the Start button, so you cannot start an exploit the app
  already knows will fail.

</details>

### 🧨 DirtyFrag

_CVE-2026-43284 — kernel page-cache write through in-place ESP decryption._

<details>
<summary><b>Supported kernels (Click to Expand)</b></summary>

- Android 12 → 5.10
- Android 13 → 5.10, 5.15
- Android 14 → 5.15, 6.1
- Android 15 → 6.6
- Android 16 → 6.12
- Android 17 → 6.18

The module is picked from your kernel branch and verified with the live probe above. On a kernel
with the mitigation backported, the probe reports **patched** and Start stays disabled.

</details>

### 👻 GhostLock

_CVE-2026-43499 — futex PI use-after-free, driven by a per-kernel profile._

<details>
<summary><b>Why GhostLock needs a profile (Click to Expand)</b></summary>

- GhostLock needs exact offsets for your kernel build, so support is an exact `uname -r` match, not
  a version range.
- Nothing is bundled: the app downloads the matching upstream profile and its shared files, compiles
  them to the GLK1 format on the device, and starts the core with that profile.
- If upstream has no profile for your build, the check says so and tells you how many profiles exist.
- Profile generation for a new kernel is done with the upstream extractor from your `boot.img` or an
  OTA link; that is a separate workflow, not something the app fakes.
- Already have a `.conf` for this exact kernel? **Settings → Import GhostLock profile** validates the
  `release` field against `uname -r` and compiles it on the spot, so a broken profile is rejected
  before you ever tap Start. Upstream profiles still win when they exist; the import is the fallback
  for kernels upstream has never built.

</details>

<details>
<summary><b>The success estimate (Click to Expand)</b></summary>

The percentage shown after a check is a heuristic, not a measurement. It starts at **50%** for an
upstream profile and **5%** for an imported one (that file was not built by the project), then moves
with things the app can actually see: free RAM, thermal status, uptime, battery level, battery saver,
doze, whether the selected manager ships `ksud`, and whether the previous run finished. Night hours
add a little, since the phone is usually idle then. Every press also gets **±7%** of run-to-run
variance, and the result is clamped to 5–95%.

Why these factors: kernel-race reliability degrades with competing load, thermal throttling and
frequency scaling, and improves with a freshly rebooted, idle, cool device — see _Playing for
K(H)eaps_ (USENIX Security '22). The core already pins its threads and does the heap spray; the app
can only control the environment around it.

</details>

### 🧩 Root Managers

_Bring your own manager — the app only needs it to ship `libksud.so`._

<details>
<summary><b>Managers and Samsung handling (Click to Expand)</b></summary>

- Pick a manager in the app and tap **Download** — the APK is fetched from that project's GitHub
  releases, preferring a universal or arm64 build, and installed in place.
- [OriginSU](https://github.com/samakshkambxj/OriginSU/releases/latest) is the default.
- Also listed: [KernelSU](https://github.com/tiann/KernelSU/releases/latest),
  [KernelSU-Next](https://github.com/KernelSU-Next/KernelSU-Next/releases/latest),
  [BakaSU](https://github.com/Baka-SU/BakaSU/releases/latest),
  [SukiSU-Ultra](https://github.com/SukiSU-Ultra/SukiSU-Ultra/releases/latest).
- On Samsung devices only the [diabl0w KernelSU fork](https://github.com/diabl0w/KernelSU/releases)
  is offered — Samsung kernels only accept its `ksud`, so showing the others would just be a broken
  button.
- Spoofed builds with random package ids are matched by app label, so they keep a working Download
  button instead of being treated as an unknown manager.

</details>

### ⚙️ Settings

_The few knobs that matter after root is up._

<details>
<summary><b>All the Settings (Click to Expand)</b></summary>

- **Launch manager after success** — open the selected manager once root is up.
- **Start at boot** — run the exploit on boot; the receiver is a normal app component, disable it
  any time.
- **Auto soft reboot** — off by default. Restarts Android services (not a full reboot) so the
  freshly loaded module binds; needed with KernelSU late-load. Applies to both exploits. While it
  is on, the manager launch below is skipped (the reboot replaces it).
- **Disable KSU modules** — writes a `disable` file into every `/data/adb/modules/<id>`, so
  modules stay off until you re-enable them in the manager. It is a blunt recovery switch, not a
  bootloop detector: it runs at root time. Applies to both exploits.
- **Import GhostLock profile** — load a `.conf` generated for this exact `uname -r`; the file is
  compiled at import so a broken profile is rejected before a run.
- **About** — version, community links, donation links and credits.

</details>

---

## 📥 Requirements & Installation

- **DirtyFrag**: GKI kernel — Android 12+ on 5.10, 5.15, 6.1, 6.6, 6.12 or 6.18. Non-GKI 4.x is not
  supported: those kernels have no Kernel Module Interface, so no shipped module can load.
- **GhostLock**: an exact `uname -r` profile published upstream.
- A KernelSU-compatible manager that ships `libksud.so`.
- Android 12 or newer, arm64 device.

1. Download the latest APK from the [Releases Tab](../../releases).
2. Open the app and pick a root manager (Download installs it for you).
3. Tap **Check device compatibility** and read the verdict.
4. If it says Vulnerable / Supported, tap **Start Temporary Root** and wait for the manager to come up.

---

## 🔬 How It Works

<details>
<summary><b>DirtyFrag chain (Click to Expand)</b></summary>

1. **IpSec transform** — the app allocates a `UdpEncapsulationSocket` and SPI and builds an
   AES-CBC/HMAC-SHA256 ESP transform through `IpSecManager`.
2. **splicehelper → crash_dump64** — the CBC primitive writes a helper into `crash_dump64`'s page
   cache. `crash_dump64` runs in the `crash_dump` domain, which may open `vendor_file`-labelled
   files that an app cannot.
3. **temproot.ko → vendor_file** — the per-KMI module is written through that bridge.
4. **libc++ hook** — shellcode patched into `libc++.so` runs in init (uid 0, tid 1), creates
   `/dev/tr` as a one-shot guard, and execs `insmod` in the `vendor_modprobe` domain.
5. **temproot.ko init** — sets SELinux permissive, bypasses DEFEX where present, and runs the
   bootstrap through `call_usermodehelper`.
6. **bootstrap** — marks `super`, `misc`, `steady` and every `*_a`/`*_b` block device read-only
   (`BLKROSET`), applies the module and soft-reboot settings, stages `ksud` from your manager's
   native libs and runs `ksud late-load`, which brings up the manager you already have.

</details>

<details>
<summary><b>GhostLock chain (Click to Expand)</b></summary>

1. The app resolves your kernel profile (HOCON) and compiles it into the GLK1 binary format.
2. `libghostlock.so` loads that profile and runs the futex PI race to hijack a victim thread.
3. It escalates to `init_cred`, bypasses seccomp, and starts an independent root shell.
4. That shell runs our `bootstrap --protect` wrapper for the manager's `ksud`: same block-device
   read-only protection, same module and soft-reboot settings as DirtyFrag.
5. It then runs the real `ksud late-load` from the manager you picked.

</details>

---

## ❓ FAQ

<details>
<summary><b>Log says patching failed and my device isn't vulnerable</b></summary>

Either your kernel is too new, your vendor backported the mitigation, or the probe hit the
accidental mitigation seen on some 6.1 builds. The live probe is the authority here — no fix exists
for a patched kernel.

</details>

<details>
<summary><b>Log says "Unsupported kernel"</b></summary>

Your kernel branch is not in the module list. Only the GKI branches above are built; vendor kernels
with a different `vermagic` cannot load these modules.

</details>

<details>
<summary><b>Root says SUCCESS or ksud errors, but I don't have root</b></summary>

Disable "Auto soft reboot" in Settings and run again. Some managers need the late-load to settle
before the reboot.

</details>

<details>
<summary><b>I installed a module and now root crashes or bootloops</b></summary>

Enable "Disable KSU modules" in Settings. If you cannot boot, Android usually falls back to Safe
Mode; from there disable "Start at boot" or uninstall the app.

</details>

<details>
<summary><b>GhostLock says unsupported for my kernel</b></summary>

Upstream publishes profiles only for kernels someone extracted offsets for. Your exact `uname -r`
is not in that list, and a nearby build string will not work — the offsets differ.

If you generated a `.conf` for this exact kernel with the upstream extractor, import it in
**Settings → Import GhostLock profile**. It is compiled and accepted on the spot, and the estimate
starts at 5% because the profile was not built by the project.

</details>

<details>
<summary><b>Is the GhostLock percentage real?</b></summary>

No, it is an **estimate**, and it only exists for GhostLock. Part of it is measured (RAM, thermals,
uptime, battery, battery saver, doze, missing `ksud`, unfinished run), part of it is ±7% deliberate
run-to-run variance, because two identical runs do not have identical odds. A high number means the
conditions are good, not that the race will land — a miss can still panic and reboot the phone.

</details>

---

## 💖 Support the Project

Temporary Root is open-source and free. If it saved you a boot image patch, consider supporting the
work:

- [Sponsor via GitHub](https://github.com/sponsors/ShivamXD6)
- [Donate via PayPal](https://www.paypal.com/paypalme/ShivamXD6)
- **BHIM / international UPI:** `shivamdhage@upi` — [QR code](https://i.ibb.co/ZRm2xN2L/bhimupi.jpg)
- **GPay / UPI (India):** `shivamashokdhage6@oksbi` — [QR code](https://i.ibb.co/5g4J2RXR/1f38d6d7-a8a2-4696-88e6-9cf503e0592c.png)
- [Join Build Bytes on Telegram](https://telegram.me/BuildBytes)

---

## 🙏 Credits

- DirtyFrag research: [v4bel](https://github.com/V4bel/dirtyfrag)
- DFRoot — the DirtyFrag chain, RO partition protection and late-load handoff this app is built
  on: [diabl0w](https://github.com/diabl0w/DFRoot)
- GhostLock research and profile format: [YuKongA](https://github.com/YuKongA/ghostlock-app)
- Unprivileged XFRM socket method: [combeng6th/DirtyInit](https://github.com/combeng6th/DirtyInit)
- SELinux permissive modules and related code: [polygraphene](https://github.com/polygraphene/DFReroot)
- `lspromise` and various code: [lsposed](https://github.com/lsposed/lspromise)
- Inter typeface (SIL Open Font License 1.1): [rsms/inter](https://github.com/rsms/inter)
- `include.inc` contains code Copyright © 2018 Odzhan, BSD-3-Clause

---

<div align="center">
  Developed by <b>@ShivamXD6</b> and the <b>Build Bytes</b> Team.<br>
  <a href="https://telegram.me/buildbytes">Join Telegram Community</a> • <a href="https://www.youtube.com/@BuildBytesX">Subscribe on YouTube</a>
</div>
