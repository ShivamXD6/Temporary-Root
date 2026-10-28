package build.bytes.temproot;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.net.IpSecManager;
import android.os.Build;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

public final class DirtyFragStrategy implements VulnerabilityStrategy {
    private static final Pattern KERNEL_VERSION = Pattern.compile("^(\\d+)\\.(\\d+)");

    // Keep in sync with app/src/main/jni/ko/ and CMakeLists.txt.
    private static final String[][] SUPPORTED_LKMS = {
            {"12", "5.10"},
            {"13", "5.10"}, {"13", "5.15"},
            {"14", "5.15"}, {"14", "6.1"},
            {"15", "6.6"},
            {"16", "6.12"},
            {"17", "6.18"},
    };

    @Override
    public String name() {
        return "DirtyFrag (CVE-2026-43284)";
    }

    @Override
    public String infoUrl() {
        return "https://github.com/v4bel/dirtyfrag";
    }

    @Override
    public CompatibilityResult inspectRunningDevice(Context context, String managerPackage, IReporter reporter) {
        IpSecManager ipsec = (IpSecManager) context.getSystemService(Context.IPSEC_SERVICE);
        if (ipsec == null) {
            return new CompatibilityResult("Runtime unavailable",
                    "This Android build does not expose IpSecManager.", false);
        }
        boolean managerOk = false;
        ApplicationInfo app = ExploitRunner.resolveManager(context, managerPackage);
        if (app != null) managerOk = ExploitRunner.hasKsud(app);

        int androidRelease = androidRelease();
        int kMajor = 0, kMinor = 0;
        String osVersion = System.getProperty("os.version", "");
        if (osVersion != null) {
            Matcher matcher = KERNEL_VERSION.matcher(osVersion);
            if (matcher.find()) {
                kMajor = Integer.parseInt(matcher.group(1));
                kMinor = Integer.parseInt(matcher.group(2));
            }
        }
        String branch = kMajor + "." + kMinor;

        String module = moduleFor(androidRelease, kMajor, kMinor);
        if (module == null) {
            if (kMajor < 5) {
                return new CompatibilityResult("Unsupported: non-GKI kernel " + branch,
                        "Kernel " + branch + " is a vendor kernel with no Kernel Module Interface. "
                                + "A module built for one device cannot load on another, so none is shipped for it.",
                        false);
            }
            return new CompatibilityResult("Unsupported kernel " + branch,
                    "No module is built for Android " + androidRelease + " on kernel " + branch
                            + ". Supported branches: 5.10, 5.15, 6.1, 6.6, 6.12, 6.18.", false);
        }

        if ("y".equals(kernelConfig("CONFIG_MODULE_SIG_FORCE"))) {
            return new CompatibilityResult("Unsupported: module signing enforced",
                    "This kernel sets CONFIG_MODULE_SIG_FORCE=y, so it rejects unsigned modules. "
                            + "The bundled module cannot load here regardless of the kernel branch.", false);
        }

        int rc = ExploitRunner.probe(context, reporter);
        if (rc == 0) {
            if (!managerOk) {
                return new CompatibilityResult("Vulnerable — no manager",
                        "Live probe wrote into the page cache. Install a root manager containing "
                                + "libksud.so to hand root over.", false);
            }
            return new CompatibilityResult("Vulnerable",
                    "Live probe wrote into the page cache. Matching module: " + module + ".", true);
        }
        if (rc == 1) {
            return new CompatibilityResult("Not vulnerable (patched)",
                    "Live probe wrote nothing back: this kernel isolates spliced pages before in-place "
                            + "ESP decrypt, so the page-cache primitive is fixed.", false);
        }
        return new CompatibilityResult("Check inconclusive",
                "The probe could not run (code " + rc + "). The kernel branch looks supported, "
                        + "but exploitability is unconfirmed.", false);
    }

    @Override
    public int launch(Context context, IReporter reporter) throws Exception {
        return ExploitRunner.run(context, reporter);
    }

    private static int androidRelease() {
        try {
            return Integer.parseInt(Build.VERSION.RELEASE);
        } catch (Exception e) {
            if (Build.VERSION.SDK_INT >= 35) return 15;
            if (Build.VERSION.SDK_INT == 34) return 14;
            if (Build.VERSION.SDK_INT == 33) return 13;
            return 12;
        }
    }

    private static String moduleFor(int androidRelease, int major, int minor) {
        String branch = major + "." + minor;
        String fallback = null;
        for (String[] lkm : SUPPORTED_LKMS) {
            if (!lkm[1].equals(branch)) continue;
            String name = "temproot-android" + lkm[0] + "-" + lkm[1] + ".ko";
            if (lkm[0].equals(String.valueOf(androidRelease))) return name;
            if (fallback == null) fallback = name;
        }
        return fallback;
    }

    /** Reads a single kernel config value; null when /proc/config.gz is unavailable or blocked. */
    private static String kernelConfig(String key) {
        File cfg = new File("/proc/config.gz");
        if (!cfg.exists()) return null;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new GZIPInputStream(new FileInputStream(cfg))))) {
            for (String line; (line = reader.readLine()) != null; ) {
                if (line.startsWith(key + "=")) return line.substring(key.length() + 1).trim();
            }
        } catch (Exception ignored) {
            // SELinux may block /proc/config.gz for apps; the live probe still decides exploitability.
        }
        return null;
    }
}
