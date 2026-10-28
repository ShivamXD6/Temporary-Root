<p align="center">
  <img src=".github/icon.png" width="96" />
</p>

# Temporary Root

Temporary Root is an Android research project for establishing a temporary privileged session and handing it to a KernelSU-compatible manager. It ships two independently documented research paths behind one manager integration:

| Vulnerability | Path | Status |
|---|---|---|
| CVE-2026-43284 (DirtyFrag) | Kernel page-cache ESP write, then a per-KMI module | Bundled, runs on GKI |
| CVE-2026-43499 (GhostLock) | Futex PI use-after-free driven by a per-`uname -r` kernel profile | Detection only in this build |

> [!IMPORTANT]
> Before hitting that fork button, consider making a pull request instead :)

## Requirements

- **DirtyFrag**: GKI kernel only — Android 12+ on a 5.10, 5.15, 6.1, 6.6, 6.12 or 6.18 kernel branch. Non-GKI 4.x kernels (4.9 / 4.14 / 4.19) are not supported: they have no Kernel Module Interface, so no shipped module can load on them. A kernel with `CONFIG_MODULE_SIG_FORCE=y` cannot load the unsigned module either.
- **GhostLock**: an exact `uname -r` match in the profile list. Profiles are fetched from the [upstream project](https://github.com/YuKongA/ghostlock-app) at check time and cached; the exploit core itself is not bundled in this build, so the app reports support but cannot run this path yet.
- A KernelSU-compatible manager that ships `libksud.so`.

Run **Check device compatibility** — it decides by a live probe (DirtyFrag) or the profile list (GhostLock), not by the kernel version alone.

## FAQ

**Q: Log says patching failed and that my device isn't vulnerable**

A: Sorry, there is no fix. Either:
  - Your device's kernel is too new
  - Your device manufacturer backported the official mitigation
  - [Accidental mitigation](https://github.com/V4bel/dirtyfrag/issues/23#issuecomment-4405314290) (typically seen on kernel 6.1)

**Q: Log says "Unsupported kernel"**

A: Your kernel branch is not in the module list. Only the GKI branches above are built; vendor kernels with a different `vermagic` cannot load these modules.

**Q: Log says "SUCCESS" or "ksud exited with error", but I don't have root**

A: First, try disabling "Auto Soft Reboot" in Temporary Root settings.

**Q: I installed a module and now I can't launch root without crashing**

A: Toggle "Disable KSU Modules" in Temporary Root settings.

**Q: I installed a module and have "Start at Boot" enabled and now I am bootlooping**

A: Typically Android will detect bootloops and boot you into Safe Mode, otherwise you can usually hold the Volume Down button during boot to enter safe mode. From there, disable "Start on Boot" and enable "Disable KSU Modules" in Temporary Root settings or uninstall Temporary Root until you stabilize.

## Usage

> [!WARNING]
> I am not responsible for any damage to your device.

1. Build and install Temporary Root.
2. Pick a root manager in the app and tap **Download** — the APK is fetched from the manager's GitHub releases and installed in place. On a Samsung device only the diabl0w KernelSU fork is offered, because Samsung kernels only accept its `ksud`.
   - [OriginSU](https://github.com/samakshkambxj/OriginSU/releases/latest) (default)
   - [KernelSU](https://github.com/tiann/KernelSU/releases/latest)
   - [KernelSU-Next](https://github.com/KernelSU-Next/KernelSU-Next/releases/latest)
   - [BakaSU](https://github.com/Baka-SU/BakaSU/releases/latest)
   - [SukiSU-Ultra](https://github.com/SukiSU-Ultra/SukiSU-Ultra/releases/latest)

> If you have a custom KernelSU fork for a specific manufacturer, please make a pull request to add it here.

## Features

- Start on Boot
- Automatic soft reboot (if `late-load --soft-reboot` supported by your SU Manager)
- Disable all KernelSU modules, to recover from a broken one
- Live vulnerability probe instead of a version guess
- Shizuku not needed — regain root without WiFi!
- Material 3 UI with bundled Inter, no Play Services dependency

## How it works

The Android kernel decrypts AES-CBC ESP packets directly into the page cache of files open for `splice()`. By crafting `IV = AES_ECB_DEC(key, current_content) ⊕ desired_content`, any 16-byte-aligned block in a mapped shared library can be overwritten without write permission and without copy-on-write.

The exploit uses this primitive to patch shellcode into `libc++.so` in the kernel's page cache. The next privileged call to the hooked function runs the shellcode, which loads our custom kernel module via `insmod`.

### Exploit chain

1. **IpSec transform** — App allocates a `UdpEncapsulationSocket` + SPI and builds an AES-CBC/HMAC-SHA256 ESP transform via `IpSecManager`.

2. **splicehelper → crash_dump64** — The splicehelper binary is spliced into `crash_dump64` via the CBC primitive. `crash_dump64` runs in the `crash_dump` SELinux domain (via exec label transition), which can open `vendor_file` labeled files (untrusted_app context cannot read these files so we need this bridge). The splicehelper serves two modes: splice mode (pipe a 16-byte page chunk out to the parent for write) and read mode (`argv[3]="r"`, write 16 bytes of file content to a pipe fd for IV computation).

3. **temproot.ko → vendor_file** — The kernel module is written via the crash_dump bridge (splicehelper splice mode) into a `vendor_file`-labeled file.

4. **libc++ hook** (fires in init, uid=0, tid=1) — Shellcode is patched into `libc++.so` at `std::ostream::sentry::sentry()` (`_ZNSt3__113basic_ostreamIcNS_11char_traitsIcEEE6sentryC1ERS3_`). Triggered by: `createOrphanProcess()` double-forks so the grandchild is adopted by PID 1 (init); when init reaps the orphan its main thread (tid=1) calls through the hooked function. The shellcode:
   - Checks `getuid()==0` and `gettid()==1`; returns immediately otherwise
   - Creates `/dev/tr` as a one-shot mutex (O_CREAT|O_EXCL) to prevent re-entry
   - Clones a worker child (parent returns to init immediately)
   - Worker forks a grandchild; grandchild writes `u:r:vendor_modprobe:s0` to `/proc/self/attr/exec` then execs `/vendor/bin/insmod <ko_target>`

5. **temproot.ko init** (runs as `vendor_modprobe`, uid=0) — The KO is loaded by `insmod` in the `vendor_modprobe` SELinux domain:
   - Writes `false` to `selinux_state` (global permissive)
   - Bypasses DEFEX via kprobes (if applicable)
   - Calls `call_usermodehelper` to run our custom bootstrap code

6. **bootstrap.c** — performs various functions and finally launches the su daemon from your installed SU Manager.

## Building

Keystore credentials are never stored in the repository:

```sh
export SIGNING_PASS='your-keystore-password'
export SIGNING_ALIAS='your-key-alias'
make
```

## Credits

- Original PoC and various code: https://github.com/lsposed/lspromise
- SELinux Permissive kernel modules and various code: https://github.com/polygraphene/DFReroot
- Unprivileged XFRM socket method: https://github.com/combeng6th/DirtyInit
- Vulnerability research (CVE-2026-43284 / CVE-2026-43500): https://github.com/V4bel/dirtyfrag
- GhostLock (CVE-2026-43499) profile format and research: https://github.com/YuKongA/ghostlock-app
- Inter typeface (SIL Open Font License 1.1): https://github.com/rsms/inter
- `include.inc` contains code Copyright © 2018 Odzhan, BSD-3-Clause
