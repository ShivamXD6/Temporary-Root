package build.bytes.temproot;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Console log persisted to disk so it survives a crash or kernel panic, plus the
 * kernel panic tail from /sys/fs/pstore, which only root can read.
 *
 * A run writes a start marker, and an end marker when the binary returns. A log
 * without the end marker means the last run died, so the next app launch tries
 * to pull the panic trace while it still has root.
 */
final class RunLog {
    private static final String NAME = "last-run.log";
    private static final String START_MARK = "=== run start";
    private static final String END_MARK = "=== run end";
    private static final String[] PSTORE = {
            "/sys/fs/pstore/console-ramoops-0",
            "/sys/fs/pstore/console-ramoops",
            "/sys/fs/pstore/dmesg-ramoops-0",
    };

    private static File cached;

    private RunLog() {
    }

    /** One log for the whole app, always in device-protected storage. */
    static synchronized File file(Context ctx) {
        if (cached == null) {
            cached = new File(ctx.createDeviceProtectedStorageContext().getFilesDir(), NAME);
        }
        return cached;
    }

    static synchronized void start(Context ctx) {
        write(ctx, START_MARK + " " + stamp() + " ===\n");
    }

    static synchronized void append(Context ctx, String text) {
        try (FileOutputStream out = new FileOutputStream(file(ctx), true)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (Exception ignored) {
        }
    }

    static synchronized void finish(Context ctx) {
        append(ctx, END_MARK + " " + stamp() + " ===\n");
    }

    static String read(Context ctx) {
        try {
            String text = new String(Files.readAllBytes(file(ctx).toPath()), StandardCharsets.UTF_8);
            return text.isEmpty() ? "No saved log yet." : text;
        } catch (Exception e) {
            return "No saved log yet.";
        }
    }

    /**
     * Appends the previous run's kernel panic trace when that run never wrote its
     * end marker. Needs root; silently does nothing without it.
     */
    static void capturePanicIfNeeded(Context ctx) {
        final String previous = read(ctx);
        if (!previous.contains(START_MARK) || previous.contains(END_MARK)) return;

        String trace = suCat();
        if (trace == null || trace.trim().isEmpty()) return;

        append(ctx, "\n=== kernel panic trace (pstore) ===\n" + trace + "\n");
    }

    private static String suCat() {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "cat " + String.join(" ", PSTORE) + " 2>/dev/null"});
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                for (String line; (line = reader.readLine()) != null; ) sb.append(line).append('\n');
            }
            return p.waitFor() == 0 ? sb.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static void write(Context ctx, String text) {
        try {
            Files.write(file(ctx).toPath(), text.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    private static String stamp() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date());
    }
}
