package build.bytes.temproot;

import android.content.Context;
import android.net.IpSecManager;
import android.net.Uri;

public final class DirtyFragStrategy implements VulnerabilityStrategy {
    @Override public String getId() { return "dirtyfrag"; }
    @Override public String getDisplayName() { return "DirtyFrag"; }

    @Override
    public CompatibilityResult inspectRunningDevice(Context context, String managerPackage) {
        if (managerPackage == null || ExploitRunner.getManagerInfo(context, managerPackage) == null) {
            return new CompatibilityResult(CompatibilityResult.Status.UNSUPPORTED,
                    "Manager unavailable", "Select a KernelSU manager containing libksud.so.");
        }
        IpSecManager ipsec = (IpSecManager) context.getSystemService(Context.IPSEC_SERVICE);
        if (ipsec == null) {
            return new CompatibilityResult(CompatibilityResult.Status.UNSUPPORTED,
                    "Runtime unavailable", "This Android build does not expose IpSecManager.");
        }
        return new CompatibilityResult(CompatibilityResult.Status.UNKNOWN,
                "Runtime check passed", "The required manager and IPsec APIs are present. A compatibility check cannot prove whether the OEM kernel backported the fix; launch remains the final test.");
    }

    @Override
    public CompatibilityResult inspectBootImage(Context context, Uri imageUri) {
        return BootImageInspector.inspect(context, imageUri);
    }

    @Override
    public int launch(Context context, IReporter reporter) throws Exception {
        return ExploitRunner.run(context, reporter);
    }

    @Override
    public int revert(Context context, IReporter reporter) throws Exception {
        return ExploitRunner.revert(context, reporter);
    }
}
