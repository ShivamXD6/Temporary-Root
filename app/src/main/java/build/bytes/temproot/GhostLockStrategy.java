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
        Set<String> profiles = profiles(context);
        if (profiles == null) {
            return new CompatibilityResult("Check inconclusive",
                    "Could not read the profile list. Connect to the internet and try again.", false);
        }
        String profile = release + ".conf";
        if (!profiles.contains(profile)) {
            Odds odds = odds(context, false);
            return new CompatibilityResult("Unsupported kernel " + release,
                    "GhostLock only runs on kernels with an exact uname -r profile and none exists for this one ("
                            + profiles.size() + " profiles published)." + odds.detail, false);
        }
        Odds odds = odds(context, true);
        return new CompatibilityResult("Supported \u2014 profile available \u00b7 ~" + odds.score + "%",
                "Upstream ships " + profile + ". Start downloads and compiles it, then runs the GhostLock core."
                        + odds.detail,
                true);
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
     * Heuristic from measurable device state, not a measurement: the primitive
     * itself is ~97% stable on a matched profile, so the score mostly reflects
     * environment (memory, heat, load) and the module handoff.
     */
    private static Odds odds(Context context, boolean supported) {
        int score = supported ? 90 : 0;
        StringBuilder notes = new StringBuilder();

        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mem = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mem);
        long freeMb = mem.availMem >> 20;
        int d = freeMb >= 2500 ? 2 : freeMb >= 1500 ? 0 : freeMb >= 800 ? -5 : -15;
        score += d;
        notes.append(note(d, freeMb + " MB free RAM"));

        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        int thermal = pm.getCurrentThermalStatus();
        d = thermal <= PowerManager.THERMAL_STATUS_LIGHT ? 0
                : thermal == PowerManager.THERMAL_STATUS_MODERATE ? -6 : -18;
        score += d;
        notes.append(note(d, "thermal " + thermalName(thermal)));

        long upMin = SystemClock.elapsedRealtime() / 60000;
        d = upMin < 30 ? 2 : upMin < 360 ? 0 : upMin < 1440 ? -4 : -8;
        score += d;
        notes.append(note(d, "up " + (upMin < 60 ? upMin + " min" : upMin / 60 + " h") + " since boot"));

        BatteryManager bm = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
        int pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        if (pct < 0 || pct > 100) pct = 50;
        d = pct >= 50 ? 0 : pct >= 20 ? -3 : -12;
        score += d;
        notes.append(note(d, "battery " + pct + "%"));

        if (pm.isPowerSaveMode()) {
            score -= 8;
            notes.append(note(-8, "battery saver is on — turn it off"));
        }

        if (managerKsud(context) == null) {
            score -= 20;
            notes.append(note(-20, "no ksud in the selected manager (root only, no KernelSU)"));
        }

        String log = RunLog.read(context);
        if (log.contains("=== run start") && !log.contains("=== run end")) {
            score -= 15;
            notes.append(note(-15, "last run never finished — reboot before retrying"));
        }

        score = Math.max(0, Math.min(97, score));
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
            if (ksud != null) pb.environment().put("GHOSTLOCK_KSUD", ksud);
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
