package build.bytes.temproot;

import android.content.Context;

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
    private static final String RAW_BASE =
            "https://raw.githubusercontent.com/YuKongA/ghostlock-app/main/app/src/main/assets/kernel_profiles/";
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
            return new CompatibilityResult("Unsupported kernel " + release,
                    "GhostLock only runs on kernels with an exact uname -r profile and none exists for this one ("
                            + profiles.size() + " profiles published).", false);
        }
        return new CompatibilityResult("Kernel profile available",
                "Profile " + profile + " exists (" + RAW_BASE + profile + "). This build has no GhostLock "
                        + "core bundled, so Start stays disabled.", false);
    }

    @Override
    public int launch(Context context, IReporter reporter) {
        reporter.report("GhostLock core is not bundled in this build.\n");
        return 2;
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
