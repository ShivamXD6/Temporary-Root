package build.bytes.temproot;

public final class CompatibilityResult {
    public enum Status { LIKELY_COMPATIBLE, LIKELY_PATCHED, UNSUPPORTED, UNKNOWN, INVALID_IMAGE }

    public final Status status;
    public final String title;
    public final String details;

    public CompatibilityResult(Status status, String title, String details) {
        this.status = status;
        this.title = title;
        this.details = details;
    }

    public String asText() {
        return title + "\n" + details;
    }
}
