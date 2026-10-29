package build.bytes.temproot;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.BatteryManager;
import android.os.PowerManager;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * CVE-2026-43499 (futex PI UAF). Unlike DirtyFrag this exploit is profile-driven:
 * it only works on kernels with an exact {@code uname -r} profile, so support is
 * decided by the profile list, not by a kernel branch.
 *
 * shortcut: profiles are fetched from the upstream repo at runtime and no
 * exploit core is bundled, so this reports support but cannot run yet; wire the
 * core when the native port lands.
 */
public final class GhostLockStrategy implements VulnerabilityStrategy {
    private static final String API_URL =
            "https://api.github.com/repos/YuKongA/ghostlock-app/contents/app/src/main/assets/kernel_profiles?ref=main";
    private static final String CACHE = "ghostlock-profiles.txt";
    private static final long CACHE_TTL_MS = 24L * 60 * 60 * 1000;

    @Override
    public String name() {
        return "GhostLock (CVE-2026-43499)";
    }

    @Override
    public String infoUrl() {
        return "https://github.com/YuKongA/ghostlock-app";
    }

    @Override
    public CompatibilityResult inspectRunningDevice(Context context, String managerPackage, IReporter reporter) {
        String release = System.getProperty("os.version", "").trim();
        if (release.isEmpty()) {
            return new CompatibilityResult("Check inconclusive",
                    "Could not read the kernel release string.", false);
        }
        // Online first: an upstream profile is the trusted one. The imported
        // file is only the fallback for kernels upstream has never built.
        String profile = release + ".conf";
        Set<String> profiles = profiles(context);
        if (profiles != null && profiles.contains(profile)) {
            Odds odds = odds(context, true);
            return new CompatibilityResult("Supported \u2014 profile available \u00b7 ~" + odds.score + "%",
                    "Upstream ships " + profile + ". Start downloads and compiles it, then runs the GhostLock core."
                            + odds.detail,
                    true);
        }
        if (new File(context.getFilesDir(), "ghostlock/" + profile).isFile()) {
            Odds odds = odds(context, true);
            return new CompatibilityResult("Supported \u2014 imported profile \u00b7 ~" + odds.score + "%",
                    "No upstream profile for this kernel; using the imported " + profile + "." + odds.detail, true);
        }
        if (profiles == null) {
            return new CompatibilityResult("Check inconclusive",
                    "Could not read the profile list. Connect to the internet and try again.", false);
        }
        Odds odds = odds(context, false);
        return new CompatibilityResult("Unsupported kernel " + release,
                "GhostLock only runs on kernels with an exact uname -r profile and none exists for this one ("
                        + profiles.size() + " profiles published)." + odds.detail, false);
    }

    /** Estimated chance of getting root right now, plus the reasons. */
    private static final class Odds {
        final int score;
        final String detail;

        Odds(int score, String detail) {
            this.score = score;
            this.detail = detail;
        }
    }

    /**
     * Heuristic from measurable device state, not a measurement. Starts at 50%
     * because the primitive is only stable when everything else lines up; the
     * notes below say what would raise or lower it.
     */
    private static Odds odds(Context context, boolean supported) {
        int score = supported ? 50 : 0;
        StringBuilder notes = new StringBuilder();

        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mem = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mem);
        long freeMb = mem.availMem >> 20;
        int d = freeMb >= 2500 ? 10 : freeMb >= 1500 ? 0 : freeMb >= 800 ? -8 : -18;
        score += d;
        notes.append(note(d, freeMb + " MB free RAM"));

        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        int thermal = pm.getCurrentThermalStatus();
        d = thermal <= PowerManager.THERMAL_STATUS_LIGHT ? 8
                : thermal == PowerManager.THERMAL_STATUS_MODERATE ? -8 : -20;
        score += d;
        notes.append(note(d, "thermal " + thermalName(thermal)));

        long upMin = SystemClock.elapsedRealtime() / 60000;
        d = upMin < 30 ? 10 : upMin < 360 ? 0 : upMin < 1440 ? -6 : -10;
        score += d;
        notes.append(note(d, "up " + (upMin < 60 ? upMin + " min" : upMin / 60 + " h") + " since boot"));

        BatteryManager bm = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
        int pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        if (pct < 0 || pct > 100) pct = 50;
        d = pct >= 50 ? 4 : pct >= 20 ? -2 : -12;
        score += d;
        notes.append(note(d, "battery " + pct + "%"));

        if (pm.isPowerSaveMode()) {
            score -= 10;
            notes.append(note(-10, "battery saver is on — turn it off"));
        }

        if (managerKsud(context) == null) {
            score -= 25;
            notes.append(note(-25, "no ksud in the selected manager (root only, no KernelSU)"));
        }

        String log = RunLog.read(context);
        if (log.contains("=== run start") && !log.contains("=== run end")) {
            score -= 15;
            notes.append(note(-15, "last run never finished — reboot before retrying"));
        }

        score = Math.max(5, Math.min(90, score));
        String detail = (supported
                ? "\n\nEstimated success ~" + score + "%  (from current device state)"
                : "\n\nEstimated success 0%  — this kernel has no GhostLock profile")
                + "\n" + notes
                + "  A missed race can panic and reboot the phone; reboot before retrying.";
        return new Odds(score, detail);
    }

    private static String note(int delta, String text) {
        return String.format(Locale.US, "  %+d  %s%n", delta, text);
    }

    private static String thermalName(int status) {
        switch (status) {
            case PowerManager.THERMAL_STATUS_NONE: return "NONE";
            case PowerManager.THERMAL_STATUS_LIGHT: return "LIGHT";
            case PowerManager.THERMAL_STATUS_MODERATE: return "MODERATE";
            case PowerManager.THERMAL_STATUS_SEVERE: return "SEVERE";
            case PowerManager.THERMAL_STATUS_CRITICAL: return "CRITICAL";
            case PowerManager.THERMAL_STATUS_EMERGENCY: return "EMERGENCY";
            case PowerManager.THERMAL_STATUS_SHUTDOWN: return "SHUTDOWN";
            default: return "unknown";
        }
    }

    @Override
    public int launch(Context context, IReporter reporter) {
        String release = System.getProperty("os.version", "").trim();
        if (release.isEmpty()) {
            reporter.report("ghostlock: cannot read kernel release\n");
            return 2;
        }
        reporter.report(odds(context, true).detail.trim() + "\n");
        try {
            File profile = GhostLockProfile.compile(context, release, reporter);
            File bin = new File(context.getApplicationInfo().nativeLibraryDir, "libghostlock.so");
            if (!bin.isFile()) {
                reporter.report("ghostlock: libghostlock.so missing from this build\n");
                return 2;
            }
            ProcessBuilder pb = new ProcessBuilder(
                    bin.getAbsolutePath(), "--load-prebuilt-profile", profile.getAbsolutePath());
            pb.redirectErrorStream(true);
            pb.environment().put("GHOSTLOCK_HOME", profile.getParentFile().getAbsolutePath());
            pb.environment().put("TMPDIR", context.getCacheDir().getAbsolutePath());
            // The handoff script falls back to a hardcoded list of managers
            // (ReSukiSU, KernelSU-Next, ...). Point it at the manager the user
            // picked so forks like OriginSU get the module instead.
            String ksud = managerKsud(context);
            if (ksud != null) pb.environment().put("GHOSTLOCK_KSUD", handoff(context, ksud, reporter));
            Process process = pb.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                for (String line; (line = reader.readLine()) != null; ) reporter.report(line + "\n");
            }
            return process.waitFor();
        } catch (Exception e) {
            reporter.report("ghostlock: " + e.getMessage() + "\n");
            return 2;
        }
    }

    /**
     * GhostLock's root script calls ksud itself. Hand it a wrapper so the
     * protections and the soft-reboot setting apply to this path too.
     * Falls back to the real ksud when the wrapper cannot be staged.
     */
    private static String handoff(Context context, String ksud, IReporter reporter) {
        try {
            File bootstrap = new File(context.getFilesDir().getParentFile(), "bootstrap");
            if (!bootstrap.isFile()) {
                Files.copy(new File(context.getApplicationInfo().nativeLibraryDir, "libbootstrap.so").toPath(),
                        bootstrap.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            bootstrap.setExecutable(true, false);

            File wrapper = new File(context.getFilesDir(), "ghostlock/ksud.sh");
            try (FileWriter out = new FileWriter(wrapper)) {
                out.write("#!/system/bin/sh\n"
                        + "\"" + bootstrap + "\" --protect \"" + ksud + "\"\n"
                        + "\"" + ksud + "\" \"$@\"\n"
                        + "rc=$?\n"
                        + "[ $rc -eq 0 ] && \"" + bootstrap + "\" --reboot \"" + ksud + "\"\n"
                        + "exit $rc\n");
            }
            if (wrapper.setExecutable(true, false)) return wrapper.getAbsolutePath();
        } catch (Exception e) {
            reporter.report("ghostlock: handoff wrapper failed, using ksud directly (" + e.getMessage() + ")\n");
        }
        return ksud;
    }

    /** ksud shipped by the manager currently selected in the app, or null. */
    private static String managerKsud(Context context) {
        try {
            String pkg = context.createDeviceProtectedStorageContext()
                    .getSharedPreferences(ExploitRunner.PREFS_NAME, Context.MODE_PRIVATE)
                    .getString(ExploitRunner.PREF_SU_MANAGER, null);
            if (pkg == null) return null;
            ApplicationInfo app = context.getPackageManager().getApplicationInfo(pkg, 0);
            File ksud = new File(app.nativeLibraryDir, "libksud.so");
            if (!ksud.isFile()) ksud = new File(app.nativeLibraryDir, "ksud");
            return ksud.isFile() ? ksud.getAbsolutePath() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Profile filenames from the upstream assets dir, cached for a day; null when unavailable. */
    private static Set<String> profiles(Context context) {
        File cache = new File(context.getFilesDir(), CACHE);
        if (cache.isFile() && System.currentTimeMillis() - cache.lastModified() < CACHE_TTL_MS) {
            Set<String> cached = read(cache);
            if (cached != null) return cached;
        }
        Set<String> fetched = fetch();
        if (fetched == null) {
            return cache.isFile() ? read(cache) : null;
        }
        try (FileWriter out = new FileWriter(cache)) {
            for (String name : fetched) out.write(name + "\n");
        } catch (Exception ignored) {
        }
        return fetched;
    }

    private static Set<String> fetch() {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(API_URL).openConnection();
            conn.setRequestProperty("User-Agent", "TemporaryRootApp");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            if (conn.getResponseCode() != 200) return null;
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                for (String line; (line = reader.readLine()) != null; ) sb.append(line);
            }
            JSONArray items = new JSONArray(sb.toString());
            Set<String> names = new LinkedHashSet<>();
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.getJSONObject(i);
                String name = item.optString("name", "");
                if (name.endsWith(".conf")) names.add(name);
            }
            return names.isEmpty() ? null : names;
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static Set<String> read(File cache) {
        Set<String> names = new LinkedHashSet<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(cache))) {
            for (String line; (line = reader.readLine()) != null; ) {
                if (line.endsWith(".conf")) names.add(line.trim());
            }
        } catch (Exception e) {
            return null;
        }
        return names.isEmpty() ? null : names;
    }
}
