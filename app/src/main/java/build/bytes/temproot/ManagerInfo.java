package build.bytes.temproot;

import android.graphics.drawable.Drawable;

public final class ManagerInfo {
    public final String packageName;
    public final CharSequence label;
    public final Drawable icon;

    public ManagerInfo(String packageName, CharSequence label, Drawable icon) {
        this.packageName = packageName;
        this.label = label;
        this.icon = icon;
    }
}
