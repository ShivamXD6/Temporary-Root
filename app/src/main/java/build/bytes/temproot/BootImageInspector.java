package build.bytes.temproot;

import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class BootImageInspector {
    private static final Pattern KERNEL = Pattern.compile("(?:Linux version )?(\\d+\\.\\d+\\.\\d+)(?:[-_][^\\s]+)?");
    private static final Pattern DATE = Pattern.compile("20\\d{2}-\\d{2}-\\d{2}");
    private static final long MAX_SCAN = 96L * 1024L * 1024L;

    private BootImageInspector() { }

    public static CompatibilityResult inspect(Context context, Uri uri) {
        if (uri == null) return new CompatibilityResult(CompatibilityResult.Status.INVALID_IMAGE, "No image selected", "Choose a boot.img file.");
        try (ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(uri, "r")) {
            if (pfd == null) throw new IOException("Unable to open file");
            long length = pfd.getStatSize();
            try (InputStream in = new FileInputStream(pfd.getFileDescriptor())) {
                byte[] header = new byte[4096];
                int count = readAtMost(in, header);
                if (count < 8 || !contains(header, count, "ANDROID!")) {
                    return new CompatibilityResult(CompatibilityResult.Status.INVALID_IMAGE,
                            "Not an Android boot image", "The selected file does not contain the ANDROID! boot header.");
                }
                StringBuilder strings = new StringBuilder();
                String kernelRelease = null;
                String securityDate = null;
                long scanned = count;
                byte[] buffer = new byte[1024 * 1024];
                StringBuilder run = new StringBuilder();
                while (scanned < MAX_SCAN) {
                    int want = (int) Math.min(buffer.length, MAX_SCAN - scanned);
                    int n = in.read(buffer, 0, want);
                    if (n < 0) break;
                    scanned += n;
                    for (int i = 0; i < n; i++) {
                        int c = buffer[i] & 0xff;
                        if (c >= 32 && c <= 126) {
                            if (run.length() < 512) run.append((char) c);
                        } else if (run.length() >= 6) {
                            strings.append(run).append('\n');
                            run.setLength(0);
                        } else run.setLength(0);
                    }
                }
                if (run.length() >= 6) strings.append(run);
                Matcher km = KERNEL.matcher(strings);
                if (km.find()) kernelRelease = km.group(1);
                Matcher dm = DATE.matcher(strings);
                if (dm.find()) securityDate = dm.group();
                return classify(uri, length, header, kernelRelease, securityDate);
            }
        } catch (Exception e) {
            return new CompatibilityResult(CompatibilityResult.Status.INVALID_IMAGE,
                    "Could not inspect image", e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    private static CompatibilityResult classify(Uri uri, long length, byte[] header,
                                                String kernel, String securityDate) {
        StringBuilder details = new StringBuilder();
        details.append("File: ").append(uri).append('\n');
        details.append("Size: ").append(length > 0 ? length + " bytes" : "unknown").append('\n');
        details.append("Header: ANDROID!").append('\n');
        if (kernel == null) {
            details.append("Kernel release string: not found\n");
            details.append("OEM backports and compressed/relocated kernel metadata cannot be determined from this scan.");
            return new CompatibilityResult(CompatibilityResult.Status.UNKNOWN, "Image found, compatibility unknown", details.toString());
        }
        details.append("Kernel release: ").append(kernel).append('\n');
        if (securityDate != null) details.append("Embedded date: ").append(securityDate).append('\n');
        int[] version = parse(kernel);
        if (isKnownFixed(version)) {
            details.append("This release is at or beyond the currently known upstream fix floor. OEM backports may differ.");
            return new CompatibilityResult(CompatibilityResult.Status.LIKELY_PATCHED, "Likely patched", details.toString());
        }
        details.append("This looks older than the known upstream fix floor, but that is not proof of vulnerability: OEM kernels can backport fixes without changing the displayed release.");
        return new CompatibilityResult(CompatibilityResult.Status.UNKNOWN, "Potentially compatible; verify on-device", details.toString());
    }

    private static int[] parse(String release) {
        String[] p = release.split("\\.");
        return new int[] { Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]) };
    }

    private static boolean isKnownFixed(int[] v) {
        if (v[0] == 5 && v[1] == 15) return v[2] >= 206;
        if (v[0] == 5 && v[1] == 10) return v[2] >= 255;
        if (v[0] == 6 && v[1] == 1) return v[2] >= 171;
        if (v[0] == 6 && v[1] == 6) return v[2] >= 138;
        if (v[0] == 6 && v[1] == 12) return v[2] >= 87;
        return v[0] >= 7;
    }

    private static int readAtMost(InputStream in, byte[] data) throws IOException {
        int total = 0, n;
        while (total < data.length && (n = in.read(data, total, data.length - total)) > 0) total += n;
        return total;
    }

    private static boolean contains(byte[] data, int length, String value) {
        byte[] target = value.getBytes(StandardCharsets.US_ASCII);
        outer: for (int i = 0; i <= length - target.length; i++) {
            for (int j = 0; j < target.length; j++) if (data[i + j] != target[j]) continue outer;
            return true;
        }
        return false;
    }
}
