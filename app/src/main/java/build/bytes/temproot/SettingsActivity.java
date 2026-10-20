package build.bytes.temproot;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;

public class SettingsActivity extends AppCompatActivity {

    private boolean isUpdatingSwitch = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        View root = findViewById(R.id.settingsRoot);
        if (root != null) {
            ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
                Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return insets;
            });
        }

        MaterialToolbar toolbar = findViewById(R.id.settingsToolbar);
        if (toolbar != null) {
            setSupportActionBar(toolbar);
            if (getSupportActionBar() != null) {
                getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            }
            toolbar.setNavigationOnClickListener(v -> finish());
        }

        Context ctx = this;
        SharedPreferences dePrefs = ctx.createDeviceProtectedStorageContext()
                .getSharedPreferences(ExploitRunner.PREFS_NAME, Context.MODE_PRIVATE);
        ComponentName bootReceiver = new ComponentName(ctx, BootReceiver.class);

        // 1. Launch Manager After Success (Default: TRUE)
        MaterialCardView cardOpenManager = findViewById(R.id.cardOpenManager);
        MaterialSwitch switchOpenManager = findViewById(R.id.switchOpenManager);
        boolean openManagerChecked = dePrefs.getBoolean(ExploitRunner.PREF_OPEN_MANAGER, true);
        switchOpenManager.setChecked(openManagerChecked);
        updateCardStyle(cardOpenManager, openManagerChecked);

        switchOpenManager.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isUpdatingSwitch) return;
            dePrefs.edit().putBoolean(ExploitRunner.PREF_OPEN_MANAGER, isChecked).apply();
            updateCardStyle(cardOpenManager, isChecked);
        });
        cardOpenManager.setOnClickListener(v -> switchOpenManager.toggle());

        // 2. Start at Boot
        MaterialCardView cardBootStart = findViewById(R.id.cardBootStart);
        MaterialSwitch switchBootStart = findViewById(R.id.switchBootStart);
        int bootState = ctx.getPackageManager().getComponentEnabledSetting(bootReceiver);
        boolean bootChecked = (bootState == PackageManager.COMPONENT_ENABLED_STATE_ENABLED);
        switchBootStart.setChecked(bootChecked);
        updateCardStyle(cardBootStart, bootChecked);

        switchBootStart.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isUpdatingSwitch) return;
            ctx.getPackageManager().setComponentEnabledSetting(bootReceiver,
                    isChecked ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                              : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP);
            updateCardStyle(cardBootStart, isChecked);
        });
        cardBootStart.setOnClickListener(v -> switchBootStart.toggle());

        // 3. Auto Soft Reboot
        MaterialCardView cardSoftReboot = findViewById(R.id.cardSoftReboot);
        MaterialSwitch switchSoftReboot = findViewById(R.id.switchSoftReboot);
        boolean softChecked = dePrefs.getBoolean("soft_reboot", false);
        switchSoftReboot.setChecked(softChecked);
        updateCardStyle(cardSoftReboot, softChecked);

        switchSoftReboot.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isUpdatingSwitch) return;
            dePrefs.edit().putBoolean("soft_reboot", isChecked).apply();
            updateCardStyle(cardSoftReboot, isChecked);
        });
        cardSoftReboot.setOnClickListener(v -> switchSoftReboot.toggle());

        // 4. Disable KSU Modules
        MaterialCardView cardDisableModules = findViewById(R.id.cardDisableModules);
        MaterialSwitch switchDisableModules = findViewById(R.id.switchDisableModules);
        boolean disableChecked = dePrefs.getBoolean("disable_modules", false);
        switchDisableModules.setChecked(disableChecked);
        updateCardStyle(cardDisableModules, disableChecked);

        switchDisableModules.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isUpdatingSwitch) return;
            dePrefs.edit().putBoolean("disable_modules", isChecked).apply();
            updateCardStyle(cardDisableModules, isChecked);
        });
        cardDisableModules.setOnClickListener(v -> switchDisableModules.toggle());

        // 5. Protect Read-Only Partitions (Default: TRUE)
        MaterialCardView cardProtectPartitions = findViewById(R.id.cardProtectPartitions);
        MaterialSwitch switchProtectPartitions = findViewById(R.id.switchProtectPartitions);
        boolean protectChecked = dePrefs.getBoolean("protect_readonly_partitions", true);
        switchProtectPartitions.setChecked(protectChecked);
        updateCardStyle(cardProtectPartitions, protectChecked);

        switchProtectPartitions.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isUpdatingSwitch) return;
            if (!isChecked) {
                // User turned switch OFF -> Prompt warning dialog!
                new MaterialAlertDialogBuilder(ctx)
                        .setTitle("Warning: Not Recommended")
                        .setMessage("Disabling partition protection allows permanent modifications to system partitions, which may brick your device or break system integrity. Are you sure you want to turn this off?")
                        .setPositiveButton("Turn Off Anyway", (dialog, which) -> {
                            dePrefs.edit().putBoolean("protect_readonly_partitions", false).apply();
                            updateCardStyle(cardProtectPartitions, false);
                        })
                        .setNegativeButton("Keep Protected", (dialog, which) -> {
                            isUpdatingSwitch = true;
                            switchProtectPartitions.setChecked(true);
                            isUpdatingSwitch = false;
                            updateCardStyle(cardProtectPartitions, true);
                        })
                        .setOnCancelListener(dialog -> {
                            isUpdatingSwitch = true;
                            switchProtectPartitions.setChecked(true);
                            isUpdatingSwitch = false;
                            updateCardStyle(cardProtectPartitions, true);
                        })
                        .show();
            } else {
                dePrefs.edit().putBoolean("protect_readonly_partitions", true).apply();
                updateCardStyle(cardProtectPartitions, true);
            }
        });
        cardProtectPartitions.setOnClickListener(v -> {
            if (switchProtectPartitions.isChecked()) {
                // Trying to toggle OFF from card click -> fire change listener by setting false
                switchProtectPartitions.setChecked(false);
            } else {
                switchProtectPartitions.setChecked(true);
            }
        });
    }

    private void updateCardStyle(MaterialCardView card, boolean enabled) {
        if (card == null) return;
        if (enabled) {
            card.setCardBackgroundColor(Color.parseColor("#122038"));
            card.setStrokeColor(Color.parseColor("#6096FF"));
            card.setStrokeWidth(Math.round(2 * getResources().getDisplayMetrics().density));
        } else {
            card.setCardBackgroundColor(Color.parseColor("#0A0A0E"));
            card.setStrokeColor(Color.parseColor("#22222C"));
            card.setStrokeWidth(Math.round(1 * getResources().getDisplayMetrics().density));
        }
    }
}
