package build.bytes.temproot;

public final class CompatibilityResult {
    public final String title;
    public final String details;
    /** True only when the exploit was confirmed to work on this device. */
    public final boolean ok;
    /** Estimated success in percent, or -1 when the strategy has no estimate. */
    public final int score;

    public CompatibilityResult(String title, String details, boolean ok) {
        this(title, details, ok, -1);
    }

    public CompatibilityResult(String title, String details, boolean ok, int score) {
        this.title = title;
        this.details = details;
        this.ok = ok;
        this.score = score;
    }

    public String asText() {
        return title + "\n" + details;
    }
}
