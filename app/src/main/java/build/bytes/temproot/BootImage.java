package build.bytes.temproot;

import android.content.ContentResolver;
import android.net.Uri;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

/**
 * Read-only inspector for Android boot images. It reports the header fields and
 * the kernel version string so a user can confirm which build an OTA/boot image
 * belongs to before generating GhostLock offsets for it. It does not compute
 * offsets: those come from kallsyms and struct layouts, not from the header.
 */
final class BootImage {
    private static final int MAX_KERNEL = 64 << 20;
    private static final int MAX_SCAN = 8 << 20;

    private BootImage() {
    }

    static String describe(ContentResolver resolver, Uri uri, String deviceRelease) throws IOException {
        try (InputStream in = resolver.openInputStream(uri)) {
            return describe(in, deviceRelease);
        }
    }

    static String describe(InputStream in, String deviceRelease) throws IOException {
        {
            if (in == null) throw new IOException("cannot open file");
            byte[] head = read(in, 0, 2048);
            if (head.length < 64) throw new IOException("file too small to be a boot image");

            ByteBuffer b = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
            String magic = new String(head, 0, 8, StandardCharsets.US_ASCII);
            StringBuilder out = new StringBuilder();

            long kernelOffset;
            int kernelSize, ramdiskSize, pageSize;
            String cmdline;

            if (magic.startsWith("ANDROID!")) {
                int version = b.getInt(40);
                if (version <= 2) {
                    pageSize = b.getInt(36);
                    kernelSize = b.getInt(8);
                    ramdiskSize = b.getInt(16);
                    cmdline = text(head, 64, 512);
                    out.append("Android boot image, header v").append(version).append('\n');
                    out.append("name: ").append(text(head, 48, 16)).append('\n');
                    kernelOffset = pageSize;
                } else {
                    pageSize = 4096;
                    kernelSize = b.getInt(8);
                    ramdiskSize = b.getInt(12);
                    cmdline = text(head, 44, 1536);
                    out.append("Android boot image, header v").append(version).append('\n');
                    kernelOffset = 4096;
                }
            } else if (magic.startsWith("VNDRBOOT")) {
                int version = b.getInt(8);
                pageSize = b.getInt(12);
                cmdline = text(head, 24, 2048);
                out.append("Android vendor_boot image, v").append(version).append('\n');
                out.append("page size: ").append(pageSize).append(" B\n");
                out.append("cmdline: ").append(cmdline.isEmpty() ? "(none)" : cmdline).append('\n');
                out.append("\nvendor_boot carries the ramdisk, not the kernel; point this at boot.img "
                        + "or payload.bin to see the kernel version.");
                return out.toString();
            } else {
                return "Not an Android boot image (magic " + printable(magic) + ").\n"
                        + "Supported: boot.img (v0-v4) and vendor_boot.img.";
            }

            out.append("page size: ").append(pageSize).append(" B\n");
            out.append("kernel: ").append(size(kernelSize))
                    .append(", ramdisk: ").append(size(ramdiskSize)).append('\n');
            out.append("cmdline: ").append(cmdline.isEmpty() ? "(none)" : cmdline).append('\n');

            if (kernelSize <= 0 || kernelOffset <= 0 || kernelSize > MAX_KERNEL) {
                out.append("\ncannot read the kernel blob from this header");
                return out.toString();
            }

            byte[] kernel = read(in, kernelOffset - head.length, kernelSize);
            String release = kernelRelease(kernel);
            if (release == null) {
                out.append("\nkernel version not readable (unknown compression)");
            } else {
                out.append("\nkernel release ").append(release);
                if (!deviceRelease.isEmpty()) {
                    out.append('\n').append(release.equals(deviceRelease)
                            ? "matches this device (" + deviceRelease + ")"
                            : "does NOT match this device (" + deviceRelease + ")");
                }
            }
            out.append("\n\nOffsets still have to be generated from this image with the GhostLock "
                    + "extractor; this tool only identifies it.");
            return out.toString();
        }
    }

    /** Release string carried by the kernel, e.g. {@code 5.15.167-android13-8-o-01144-g371cef69d4b6}. */
    private static String kernelRelease(byte[] kernel) {
        int magic = kernel.length >= 4 ? ((kernel[0] & 0xff) << 24) | ((kernel[1] & 0xff) << 16)
                | ((kernel[2] & 0xff) << 8) | (kernel[3] & 0xff) : 0;
        if (kernel.length > 2 && (kernel[0] & 0xff) == 0x1f && (kernel[1] & 0xff) == 0x8b) {
            try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(kernel))) {
                return findRelease(read(gz, MAX_SCAN));
            } catch (Exception e) {
                return null;
            }
        }
        if (magic == 0xfd377a58 || magic == 0x04224d18 || magic == 0x02214c18 || magic == 0x28b52ffd) {
            return null; // xz / lz4 / zstd: no decoder in the platform
        }
        return findRelease(kernel);
    }

    /** GKI releases always contain {@code -androidNN-}; that token is unambiguous in the image. */
    private static String findRelease(byte[] data) {
        byte[] needle = "android".getBytes(StandardCharsets.US_ASCII);
        outer:
        for (int i = 0; i + needle.length < data.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (data[i + j] != needle[j]) continue outer;
            }
            int start = i;
            while (start > 0 && isReleaseChar(data[start - 1]) && i - start < 96) start--;
            int end = i + needle.length;
            while (end < data.length && isReleaseChar(data[end]) && end - start < 160) end++;
            String candidate = new String(data, start, end - start, StandardCharsets.US_ASCII);
            if (candidate.matches("[0-9]+\\.[0-9]+\\.[0-9]+-[A-Za-z0-9._+-]*android[0-9]+-[A-Za-z0-9._+-]+")) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean isReleaseChar(byte c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z')
                || (c >= 'A' && c <= 'Z') || c == '.' || c == '-' || c == '_' || c == '+';
    }

    /** Reads [offset, offset+length) from the stream, skipping forward as needed. */
    private static byte[] read(InputStream in, long offset, int length) throws IOException {
        byte[] out = new byte[length];
        int filled = 0;
        long toSkip = offset;
        while (toSkip > 0) {
            long skipped = in.skip(toSkip);
            if (skipped <= 0) {
                if (in.read() < 0) break;
                skipped = 1;
            }
            toSkip -= skipped;
        }
        while (filled < length) {
            int read = in.read(out, filled, length - filled);
            if (read < 0) break;
            filled += read;
        }
        if (filled == length) return out;
        byte[] shorter = new byte[filled];
        System.arraycopy(out, 0, shorter, 0, filled);
        return shorter;
    }

    private static byte[] read(InputStream in, int length) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        for (int read; total < length && (read = in.read(buffer, 0, Math.min(buffer.length, length - total))) > 0; ) {
            out.write(buffer, 0, read);
            total += read;
        }
        return out.toByteArray();
    }

    private static String text(byte[] data, int offset, int length) {
        int end = offset;
        while (end < offset + length && end < data.length && data[end] != 0) end++;
        return new String(data, offset, end - offset, StandardCharsets.US_ASCII).trim();
    }

    private static String printable(String s) {
        StringBuilder out = new StringBuilder();
        for (char c : s.toCharArray()) out.append(c >= 32 && c < 127 ? c : '.');
        return out.toString();
    }

    private static String size(int bytes) {
        if (bytes <= 0) return "none";
        return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
