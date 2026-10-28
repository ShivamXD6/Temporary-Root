package build.bytes.temproot;

public final class CompatibilityResult {
    public final String title;
    public final String details;
    /** True only when the exploit was confirmed to work on this device. */
    public final boolean ok;

    public CompatibilityResult(String title, String details, boolean ok) {
        this.title = title;
        this.details = details;
        this.ok = ok;
    }

    public String asText() {
        return title + "\n" + details;
    }
}
