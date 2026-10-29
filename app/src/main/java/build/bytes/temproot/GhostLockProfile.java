package build.bytes.temproot;

import android.content.Context;

import com.ghostlock.app.data.profile.ProfileCompiler;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetches a GhostLock kernel profile and compiles it into the GLK1 binary the
 * native core reads. Profiles are per exact {@code uname -r}, so nothing is
 * bundled: only the files this device needs are downloaded, then cached.
 */
final class GhostLockProfile {
    private static final String RAW =
            "https://raw.githubusercontent.com/YuKongA/ghostlock-app/main/app/src/main/assets/kernel_profiles/";
    private static final Pattern INCLUDE = Pattern.compile("^\\s*include\\s+\"?([^\"\\s]+)\"?\\s*$");
    /** Route presets the compiler merges in; not every route file exists. */
    private static final String[] PRESETS = {
            "execution-tuning.conf",
            "execution-tcp-zerocopy.conf",
            "execution-select-stack.conf",
            "execution-multicast-waiter.conf",
    };

    private GhostLockProfile() {
    }

    static File compile(Context ctx, String release, IReporter reporter) throws Exception {
        File dir = new File(ctx.getFilesDir(), "ghostlock");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);

        File profile = new File(dir, release + ".conf");
        boolean downloaded = false;
        if (!profile.isFile() || profile.length() == 0) {
            reporter.report("downloading profile " + release + ".conf\n");
            if (!fetch(profile, release + ".conf")) {
                throw new IOException("no upstream profile for " + release);
            }
            downloaded = true;
        }
        List<String> includes = includes(profile);
        for (String include : includes) {
            if (!download(dir, include)) {
                throw new IOException("missing include " + include);
            }
        }
        // Presets only matter for upstream profiles; an imported conf is self-contained.
        if (downloaded || !includes.isEmpty()) {
            for (String preset : PRESETS) {
                download(dir, preset); // optional
            }
        }

        reporter.report("compiling profile...\n");
        File out = new File(dir, release + ".glk1");
        Files.write(out.toPath(), ProfileCompiler.compile(profile));
        reporter.report("profile ready (" + out.length() + " bytes)\n");
        return out;
    }

    private static List<String> includes(File profile) throws IOException {
        List<String> names = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(profile.toPath())) {
            for (String line; (line = reader.readLine()) != null; ) {
                Matcher matcher = INCLUDE.matcher(line);
                if (matcher.matches()) names.add(matcher.group(1));
            }
        }
        return names;
    }

    private static boolean download(File dir, String name) throws IOException {
        File target = new File(dir, name);
        if (target.isFile() && target.length() > 0) return true;
        return fetch(target, name);
    }

    /** @return false when upstream has no such file (404), which is normal for presets. */
    private static boolean fetch(File target, String name) throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(RAW + name).openConnection();
            conn.setRequestProperty("User-Agent", "TemporaryRootApp");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            int code = conn.getResponseCode();
            if (code == 404) return false;
            if (code != 200) throw new IOException("HTTP " + code + " for " + name);
            if (!target.getParentFile().isDirectory() && !target.getParentFile().mkdirs()) {
                throw new IOException("cannot create " + target.getParentFile());
            }
            try (InputStream in = conn.getInputStream();
                 FileOutputStream out = new FileOutputStream(target)) {
                byte[] buffer = new byte[8192];
                for (int read; (read = in.read(buffer)) > 0; ) out.write(buffer, 0, read);
            }
            return true;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
