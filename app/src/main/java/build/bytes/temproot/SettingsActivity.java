package build.bytes.temproot;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.materialswitch.MaterialSwitch;

public class SettingsActivity extends AppCompatActivity {

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
        cardOpenManager.setOnClickListener(v -> switchOpenManager.toggle());

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
        switchSoftReboot.setOnCheckedChangeListener((buttonView, isChecked) ->
                dePrefs.edit().putBoolean(ExploitRunner.PREF_SOFT_REBOOT, isChecked).apply());
        cardSoftReboot.setOnClickListener(v -> switchSoftReboot.toggle());

        // 4. Disable KSU Modules
        View cardDisableModules = findViewById(R.id.cardDisableModules);
        MaterialSwitch switchDisableModules = findViewById(R.id.switchDisableModules);
        switchDisableModules.setChecked(dePrefs.getBoolean("disable_modules", false));
        switchDisableModules.setOnCheckedChangeListener((buttonView, isChecked) ->
                dePrefs.edit().putBoolean("disable_modules", isChecked).apply());
        cardDisableModules.setOnClickListener(v -> switchDisableModules.toggle());

        // 5. About
        View cardAbout = findViewById(R.id.cardAbout);
        cardAbout.setOnClickListener(v -> startActivity(new Intent(ctx, AboutActivity.class)));
    }
}
