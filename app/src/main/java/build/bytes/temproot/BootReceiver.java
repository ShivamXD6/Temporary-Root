package build.bytes.temproot;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.util.Log;

import java.io.File;

public class BootReceiver extends BroadcastReceiver implements IReporter {
    private static final String TAG = "temporaryroot";
    private Context appContext;

    @Override
    public void report(String msg) {
        Log.i(TAG, msg.trim());
        if (appContext != null) RunLog.append(appContext, msg);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (new File("/dev/tr").exists()) {
            Log.i(TAG, "boot: already hooked, skipping");
            return;
        }
        Log.i(TAG, "boot: " + intent.getAction());
        final Context deCtx = context.createDeviceProtectedStorageContext();
        appContext = deCtx;
        RunLog.start(deCtx);
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "temporaryroot:boot");
        wl.acquire();
        new Thread(() -> {
            try {
                int rc = ExploitRunner.run(deCtx, this);
                Log.i(TAG, "boot: exploit rc=" + rc);
            } catch (Exception e) {
                Log.e(TAG, "boot: exploit exception", e);
            } finally {
                RunLog.finish(deCtx);
                wl.release();
            }
        }, "temporaryroot-boot").start();
    }
}
