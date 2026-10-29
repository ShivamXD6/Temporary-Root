package build.bytes.temproot;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SettingsActivity extends AppCompatActivity {

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ActivityResultLauncher<String[]> pickProfile =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(),
                    uri -> { if (uri != null) importProfile(uri); });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        View root = findViewById(R.id.settingsRoot);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });

        MaterialToolbar toolbar = findViewById(R.id.settingsToolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        Context ctx = this;
        SharedPreferences dePrefs = ctx.createDeviceProtectedStorageContext()
                .getSharedPreferences(ExploitRunner.PREFS_NAME, Context.MODE_PRIVATE);
        ComponentName bootReceiver = new ComponentName(ctx, BootReceiver.class);

        // 1. Launch Manager After Success (Default: TRUE)
        View cardOpenManager = findViewById(R.id.cardOpenManager);
        MaterialSwitch switchOpenManager = findViewById(R.id.switchOpenManager);
        switchOpenManager.setChecked(dePrefs.getBoolean(ExploitRunner.PREF_OPEN_MANAGER, true));
        switchOpenManager.setOnCheckedChangeListener((buttonView, isChecked) ->
                dePrefs.edit().putBoolean(ExploitRunner.PREF_OPEN_MANAGER, isChecked).apply());

        // 2. Start at Boot
        View cardBootStart = findViewById(R.id.cardBootStart);
        MaterialSwitch switchBootStart = findViewById(R.id.switchBootStart);
        int bootState = ctx.getPackageManager().getComponentEnabledSetting(bootReceiver);
        switchBootStart.setChecked(bootState == PackageManager.COMPONENT_ENABLED_STATE_ENABLED);
        switchBootStart.setOnCheckedChangeListener((buttonView, isChecked) ->
                ctx.getPackageManager().setComponentEnabledSetting(bootReceiver,
                        isChecked ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                                  : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP));
        cardBootStart.setOnClickListener(v -> switchBootStart.toggle());

        // 3. Auto Soft Reboot
        View cardSoftReboot = findViewById(R.id.cardSoftReboot);
        MaterialSwitch switchSoftReboot = findViewById(R.id.switchSoftReboot);
        switchSoftReboot.setChecked(dePrefs.getBoolean(ExploitRunner.PREF_SOFT_REBOOT, false));
        switchSoftReboot.setOnCheckedChangeListener((buttonView, isChecked) -> {
            dePrefs.edit().putBoolean(ExploitRunner.PREF_SOFT_REBOOT, isChecked).apply();
            syncManagerRow(isChecked);
        });
        cardSoftReboot.setOnClickListener(v -> switchSoftReboot.toggle());
        cardOpenManager.setOnClickListener(v -> {
            if (!switchSoftReboot.isChecked()) switchOpenManager.toggle();
        });
        syncManagerRow(switchSoftReboot.isChecked());

        // 4. Disable KSU Modules
        View cardDisableModules = findViewById(R.id.cardDisableModules);
        MaterialSwitch switchDisableModules = findViewById(R.id.switchDisableModules);
        switchDisableModules.setChecked(dePrefs.getBoolean("disable_modules", false));
        switchDisableModules.setOnCheckedChangeListener((buttonView, isChecked) ->
                dePrefs.edit().putBoolean("disable_modules", isChecked).apply());
        cardDisableModules.setOnClickListener(v -> switchDisableModules.toggle());

        // 5. Import a GhostLock profile (.conf) for this exact kernel
        findViewById(R.id.cardImportProfile).setOnClickListener(v -> pickProfile.launch(new String[]{"*/*"}));

        // 6. About
        View cardAbout = findViewById(R.id.cardAbout);
        cardAbout.setOnClickListener(v -> startActivity(new Intent(ctx, AboutActivity.class)));
    }

    /** The soft reboot happens at the end of the run, so opening the manager first is pointless. */
    private void syncManagerRow(boolean softReboot) {
        ((MaterialSwitch) findViewById(R.id.switchOpenManager)).setEnabled(!softReboot);
        findViewById(R.id.cardOpenManager).setAlpha(softReboot ? 0.4f : 1f);
        ((TextView) findViewById(R.id.txtOpenManagerHint)).setText(softReboot
                ? "Skipped while auto soft reboot is on"
                : "Open the selected manager once root is up");
    }

    /**
     * The release in the file must match this kernel: every offset is build
     * specific. Compiling here proves the file is usable before a run.
     */
    private void importProfile(Uri uri) {
        Toast.makeText(this, "Importing\u2026", Toast.LENGTH_SHORT).show();
        io.execute(() -> {
            String message;
            try {
                String text = readText(uri);
                Matcher matcher = Pattern.compile("(?m)^\\s*release\\s*=\\s*\"([^\"]+)\"").matcher(text);
                if (!matcher.find()) throw new IOException("no release field, not a GhostLock profile");
                String release = matcher.group(1).trim();
                String device = System.getProperty("os.version", "").trim();
                if (!release.equals(device)) {
                    throw new IOException("profile is for " + release + ", this device runs " + device);
                }
                // The runner reads profiles from device-protected storage.
                File dir = new File(createDeviceProtectedStorageContext().getFilesDir(), "ghostlock");
                if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
                Files.write(new File(dir, release + ".conf").toPath(), text.getBytes(StandardCharsets.UTF_8));
                GhostLockProfile.compile(this, release, msg -> { });
                message = "Profile imported and compiled for " + release;
            } catch (Exception e) {
                message = "Import failed: " + e.getMessage();
            }
            String result = message;
            main.post(() -> Toast.makeText(this, result, Toast.LENGTH_LONG).show());
        });
    }

    private String readText(Uri uri) throws IOException {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("cannot read the selected file");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int n; (n = in.read(buffer)) > 0; ) out.write(buffer, 0, n);
            return out.toString("UTF-8");
        }
    }
}
