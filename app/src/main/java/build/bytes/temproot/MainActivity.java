package build.bytes.temproot;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity implements IReporter {
    private static final String TAG = "temporaryroot";
    private Button btnRun, btnOpenManager, btnDownloadManager;
    private ScrollView outputScroll;
    private TextView outputView, statusView;
    private Spinner spinnerSuManager, spinnerVulnerability;
    private View logContentContainer;
    private ImageView btnToggleLog;
    private boolean isLogExpanded = false;
    private boolean isStoppedByUser = false;
    private Context mDeCtx;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final Executor mExec = Executors.newSingleThreadExecutor();
    private final List<ManagerEntry> managers = new ArrayList<>();
    private VulnerabilityStrategy strategy = new DirtyFragStrategy();
    private ManagerEntry selectedEntry;
    private String selectedManager;
    private long activeDownloadId = -1;

    private final BroadcastReceiver downloadReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
            if (id != -1 && id == activeDownloadId) {
                DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                if (dm != null) {
                    Uri apkUri = dm.getUriForDownloadedFile(id);
                    if (apkUri != null) {
                        Toast.makeText(context, "Download complete. Launching installer…", Toast.LENGTH_LONG).show();
                        
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !getPackageManager().canRequestPackageInstalls()) {
                            Toast.makeText(context, "Please enable 'Allow from this source' to install the manager", Toast.LENGTH_LONG).show();
                            try {
                                Intent allowIntent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                                        .setData(Uri.parse("package:" + getPackageName()))
                                        .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                startActivity(allowIntent);
                            } catch (Exception ignored) {
                            }
                        } else {
                            Intent install = new Intent(Intent.ACTION_VIEW);
                            install.setDataAndType(apkUri, "application/vnd.android.package-archive");
                            install.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            try {
                                startActivity(install);
                            } catch (Exception e) {
                                Toast.makeText(context, "Failed to launch installer: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                            }
                        }
                    }
                }
            }
        }
    };

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    @Override public void report(String msg) {
        Log.i(TAG, msg.trim());
        mMain.post(() -> { outputView.append(msg); outputScroll.post(() -> outputScroll.fullScroll(View.FOCUS_DOWN)); });
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        mDeCtx = createDeviceProtectedStorageContext();
        setContentView(R.layout.activity_main);

        View mainRoot = findViewById(R.id.mainRoot);
        if (mainRoot != null) {
            ViewCompat.setOnApplyWindowInsetsListener(mainRoot, (v, insets) -> {
                Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
                v.setPadding(bars.left + dpToPx(16), bars.top + dpToPx(8), bars.right + dpToPx(16), bars.bottom + dpToPx(12));
                return insets;
            });
        }
        
        btnRun = findViewById(R.id.btnRun);
        btnOpenManager = findViewById(R.id.btnOpenManager);
        btnDownloadManager = findViewById(R.id.btnDownloadManager);
        outputScroll = findViewById(R.id.outputScroll);
        outputView = findViewById(R.id.outputView);
        statusView = findViewById(R.id.statusView);
        spinnerSuManager = findViewById(R.id.spinnerSuManager);
        spinnerVulnerability = findViewById(R.id.spinnerVulnerability);
        logContentContainer = findViewById(R.id.logContentContainer);
        btnToggleLog = findViewById(R.id.btnToggleLog);
        View logHeader = findViewById(R.id.logHeader);

        if (outputScroll != null) {
            outputScroll.setOnTouchListener((v, event) -> {
                v.getParent().requestDisallowInterceptTouchEvent(true);
                if ((event.getAction() & MotionEvent.ACTION_MASK) == MotionEvent.ACTION_UP) {
                    v.getParent().requestDisallowInterceptTouchEvent(false);
                    v.performClick();
                }
                return false;
            });
        }

        View.OnClickListener toggleLogListener = v -> {
            isLogExpanded = !isLogExpanded;
            if (logContentContainer != null) {
                logContentContainer.setVisibility(isLogExpanded ? View.VISIBLE : View.GONE);
            }
            if (btnToggleLog != null) {
                btnToggleLog.setImageResource(isLogExpanded ? R.drawable.ic_expand_less : R.drawable.ic_expand_more);
            }
        };
        if (logHeader != null) logHeader.setOnClickListener(toggleLogListener);
        if (btnToggleLog != null) btnToggleLog.setOnClickListener(toggleLogListener);

        spinnerVulnerability.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new String[] { "DirtyFrag" }));
        
        View btnVulnerabilityInfo = findViewById(R.id.btnVulnerabilityInfo);
        if (btnVulnerabilityInfo != null) {
            btnVulnerabilityInfo.setOnClickListener(v -> {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/v4bel/dirtyfrag")));
                } catch (Exception e) {
                    Toast.makeText(this, "Unable to open link: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            });
        }

        loadManagers();

        spinnerSuManager.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < managers.size()) {
                    selectedEntry = managers.get(position);
                    if (selectedEntry.isInstalled && selectedEntry.hasKsud) {
                        selectedManager = selectedEntry.installedPackageName;
                        mDeCtx.getSharedPreferences(ExploitRunner.PREFS_NAME, MODE_PRIVATE)
                                .edit().putString(ExploitRunner.PREF_SU_MANAGER, selectedManager).apply();
                    } else {
                        selectedManager = null;
                    }
                } else {
                    selectedEntry = null;
                    selectedManager = null;
                }
                updateButtons();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {
                selectedEntry = null;
                selectedManager = null;
                updateButtons();
            }
        });

        View btnSettings = findViewById(R.id.btnSettings);
        if (btnSettings != null) {
            btnSettings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        }

        findViewById(R.id.btnCheckDevice).setOnClickListener(v -> checkDevice());
        findViewById(R.id.btnCopyLog).setOnClickListener(v -> copyLog());
        findViewById(R.id.btnShareLog).setOnClickListener(v -> shareLog());
        btnOpenManager.setOnClickListener(v -> openManager());
        btnDownloadManager.setOnClickListener(v -> downloadManager());
        btnRun.setOnClickListener(v -> {
            btnRun.setEnabled(false);
            if (isRootActive()) {
                mExec.execute(this::revertExploit);
            } else {
                outputView.setText("");
                mExec.execute(this::runExploit);
            }
        });

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(downloadReceiver, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(downloadReceiver, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
        }

        updateButtons();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(downloadReceiver);
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadManagers();
    }

    private List<ManagerEntry> createKnownManagers() {
        List<ManagerEntry> list = new ArrayList<>();

        // 1. Official KernelSU
        list.add(new ManagerEntry(
                "Official KernelSU",
                "Original project · Standard Manager",
                "tiann/KernelSU",
                "https://github.com/tiann/KernelSU/releases/latest",
                "me.weishu.kernelsu",
                Collections.singletonList("me.weishu.kernelsu")
        ));

        // 2. KernelSU-Next
        list.add(new ManagerEntry(
                "KernelSU-Next",
                "Extended kernel support and SUSFS integration",
                "KernelSU-Next/KernelSU-Next",
                "https://github.com/KernelSU-Next/KernelSU-Next/releases/latest",
                "com.rifs.kernelsu",
                Arrays.asList("com.rifs.kernelsu", "com.kernelsu.next")
        ));

        // 3. BakaSU
        list.add(new ManagerEntry(
                "BakaSU",
                "Formerly ReSukiSU; multi-manager support",
                "Baka-SU/BakaSU",
                "https://github.com/Baka-SU/BakaSU/releases/latest",
                "com.baka.sukisu",
                Arrays.asList("com.baka.sukisu", "io.github.bakasu")
        ));

        // 4. SukiSU-Ultra
        list.add(new ManagerEntry(
                "SukiSU-Ultra",
                "KPM support and SUSFS management",
                "SukiSU-Ultra/SukiSU-Ultra",
                "https://github.com/SukiSU-Ultra/SukiSU-Ultra/releases/latest",
                "com.sukisu.ultra",
                Arrays.asList("com.sukisu.ultra", "com.sukisu.manager", "com.resukisu.manager")
        ));

        // 5. Samsung KernelSU (diabl0w) - ONLY for Samsung phones!
        boolean isSamsung = Build.MANUFACTURER.equalsIgnoreCase("samsung") || Build.BRAND.equalsIgnoreCase("samsung");
        if (isSamsung) {
            list.add(new ManagerEntry(
                    "Samsung KernelSU",
                    "Diabl0w fork · Optimized for Samsung Knox & Kernels",
                    "diabl0w/KernelSU",
                    "https://github.com/diabl0w/KernelSU/releases",
                    "me.weishu.kernelsu",
                    Collections.singletonList("me.weishu.kernelsu")
            ));
        }

        return list;
    }

    private void loadManagers() {
        managers.clear();
        PackageManager pm = getPackageManager();

        List<ManagerEntry> knownList = createKnownManagers();
        Set<String> claimedPackages = new HashSet<>();

        for (ManagerEntry entry : knownList) {
            entry.isInstalled = false;
            entry.hasKsud = false;
            for (String pkg : entry.candidatePackages) {
                try {
                    ApplicationInfo app = pm.getApplicationInfo(pkg, 0);
                    entry.isInstalled = true;
                    entry.installedPackageName = pkg;
                    entry.appLabel = pm.getApplicationLabel(app);
                    entry.appIcon = pm.getApplicationIcon(app);
                    entry.hasKsud = ExploitRunner.hasKsud(app);
                    claimedPackages.add(pkg);
                    break;
                } catch (PackageManager.NameNotFoundException ignored) {
                }
            }
            managers.add(entry);
        }

        for (ApplicationInfo app : pm.getInstalledApplications(0)) {
            if ((app.flags & ApplicationInfo.FLAG_SYSTEM) == 0
                    && !claimedPackages.contains(app.packageName)
                    && ExploitRunner.hasKsud(app)) {
                ManagerEntry customEntry = new ManagerEntry(
                        pm.getApplicationLabel(app).toString(),
                        "Installed Custom Manager",
                        null,
                        null,
                        app.packageName,
                        Collections.singletonList(app.packageName)
                );
                customEntry.isInstalled = true;
                customEntry.hasKsud = true;
                customEntry.installedPackageName = app.packageName;
                customEntry.appLabel = pm.getApplicationLabel(app);
                customEntry.appIcon = pm.getApplicationIcon(app);
                customEntry.isCustom = true;
                managers.add(customEntry);
                claimedPackages.add(app.packageName);
            }
        }

        // Sort managers so INSTALLED ones appear at the TOP!
        managers.sort((a, b) -> {
            boolean aInst = a.isInstalled && a.hasKsud;
            boolean bInst = b.isInstalled && b.hasKsud;
            if (aInst != bInst) {
                return aInst ? -1 : 1;
            }
            return 0;
        });

        ManagerAdapter adapter = new ManagerAdapter(this, managers);
        spinnerSuManager.setAdapter(adapter);

        SharedPreferences prefs = mDeCtx.getSharedPreferences(ExploitRunner.PREFS_NAME, MODE_PRIVATE);
        String saved = prefs.getString(ExploitRunner.PREF_SU_MANAGER, null);
        int selectedIndex = 0;
        if (saved != null) {
            for (int i = 0; i < managers.size(); i++) {
                if (saved.equals(managers.get(i).getEffectivePackageName())) {
                    selectedIndex = i;
                    break;
                }
            }
        }
        spinnerSuManager.setSelection(selectedIndex);
        if (!managers.isEmpty()) {
            selectedEntry = managers.get(selectedIndex);
            if (selectedEntry.isInstalled && selectedEntry.hasKsud) {
                selectedManager = selectedEntry.installedPackageName;
            } else {
                selectedManager = null;
            }
        }
        updateButtons();
    }

    private boolean isRootActive() {
        if (isStoppedByUser) return false;
        if (!new File("/dev/tr").exists()) return false;
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "id"});
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line = reader.readLine();
            p.waitFor();
            return line != null && line.contains("uid=0");
        } catch (Exception e) {
            return false;
        }
    }

    private void updateButtons() {
        boolean isInstalled = selectedEntry != null && selectedEntry.isInstalled && selectedEntry.hasKsud;
        boolean hasDownload = selectedEntry != null && (selectedEntry.githubRepo != null || selectedEntry.fallbackWebUrl != null);
        boolean isActive = isRootActive();

        btnOpenManager.setEnabled(isInstalled);
        btnDownloadManager.setEnabled(hasDownload);

        if (isActive) {
            btnRun.setEnabled(true);
            btnRun.setText("Stop Temporary Root");
            btnRun.setBackgroundTintList(ColorStateList.valueOf(0xFFEF4444));
            statusView.setText("Temporary Root Active • Tap 'Stop' to unload without reboot");
        } else {
            btnRun.setEnabled(isInstalled);
            btnRun.setText("Start Temporary Root");
            btnRun.setBackgroundTintList(ColorStateList.valueOf(0xFF6096FF));
            if (selectedEntry == null) {
                statusView.setText("Select a KernelSU manager to continue");
            } else if (isInstalled) {
                statusView.setText("Ready • " + selectedEntry.title + " (" + selectedEntry.installedPackageName + ")");
            } else if (selectedEntry.isInstalled && !selectedEntry.hasKsud) {
                statusView.setText("Warning: " + selectedEntry.title + " is installed but missing libksud.so");
            } else {
                statusView.setText(selectedEntry.title + " is not installed. Tap 'Download' to fetch APK directly.");
            }
        }
    }

    private void checkDevice() {
        CompatibilityResult result = strategy.inspectRunningDevice(this, selectedManager);
        outputView.setText("[Device check]\n" + result.asText() + "\n\nKernel: " + System.getProperty("os.version", "unknown") + "\nAndroid security patch: " + Build.VERSION.SECURITY_PATCH + "\n");
        statusView.setText(result.title);
    }

    private void runExploit() {
        try {
            isStoppedByUser = false;
            int rc = strategy.launch(mDeCtx, this);
            if (rc != 0) {
                isStoppedByUser = true;
                File devTr = new File("/dev/tr");
                if (devTr.exists()) {
                    devTr.delete();
                }
            }
            String msg = rc == 0 ? "Temporary Root: SUCCESS" : "Temporary Root: Error (code " + rc + ")";
            report("\n" + msg + "\n");
            if (rc == 0 && mDeCtx.getSharedPreferences(ExploitRunner.PREFS_NAME, MODE_PRIVATE).getBoolean(ExploitRunner.PREF_OPEN_MANAGER, true)) {
                mMain.post(this::openManager);
            }
            mMain.post(() -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show());
        } catch (Exception e) {
            isStoppedByUser = true;
            File devTr = new File("/dev/tr");
            if (devTr.exists()) {
                devTr.delete();
            }
            Log.e(TAG, "exploit exception", e);
            report("\nexception: " + e + "\n");
        } finally {
            mMain.post(this::updateButtons);
        }
    }

    private void revertExploit() {
        try {
            isStoppedByUser = true;
            int rc = strategy.revert(mDeCtx, this);
            String msg = rc == 0 ? "Stop Temporary Root: SUCCESS" : "Stop Error (code " + rc + ")";
            mMain.post(() -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show());
        } catch (Exception e) {
            Log.e(TAG, "revert exception", e);
            report("\nrevert exception: " + e + "\n");
        } finally {
            mMain.post(this::updateButtons);
        }
    }

    private void openManager() {
        if (selectedManager == null) return;
        Intent launch = getPackageManager().getLaunchIntentForPackage(selectedManager);
        if (launch == null) {
            Toast.makeText(this, "No launch activity for this manager", Toast.LENGTH_LONG).show();
        } else {
            startActivity(launch);
        }
    }

    private void downloadManager() {
        if (selectedEntry == null) return;

        // Pre-download check: Check if unknown app sources permission is granted on Android 8.0+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(this, "Please enable 'Allow from this source' permission to install downloaded APKs", Toast.LENGTH_LONG).show();
            try {
                Intent allowIntent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                        .setData(Uri.parse("package:" + getPackageName()))
                        .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(allowIntent);
            } catch (Exception ignored) {
            }
            return;
        }

        btnDownloadManager.setEnabled(false);
        statusView.setText("Fetching latest " + selectedEntry.title + " release APK…");
        Toast.makeText(this, "Checking latest release on GitHub…", Toast.LENGTH_SHORT).show();

        mExec.execute(() -> {
            String standardUrl = null;
            String standardFileName = null;
            String spoofedUrl = null;
            String spoofedFileName = null;

            if (selectedEntry.githubRepo != null) {
                try {
                    String apiUrl = "https://api.github.com/repos/" + selectedEntry.githubRepo + "/releases/latest";
                    URL url = new URL(apiUrl);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestProperty("User-Agent", "TemporaryRootApp");
                    conn.setConnectTimeout(8000);
                    conn.setReadTimeout(8000);

                    if (conn.getResponseCode() == 200) {
                        BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                        StringBuilder sb = new StringBuilder();
                        for (String line; (line = reader.readLine()) != null; ) sb.append(line);
                        reader.close();

                        JSONObject release = new JSONObject(sb.toString());
                        JSONArray assets = release.optJSONArray("assets");
                        if (assets != null) {
                            for (int i = 0; i < assets.length(); i++) {
                                JSONObject asset = assets.getJSONObject(i);
                                String name = asset.optString("name", "");
                                String downloadUrl = asset.optString("browser_download_url", "");
                                if (name.endsWith(".apk") && !downloadUrl.isEmpty()) {
                                    String lower = name.toLowerCase();
                                    if (lower.contains("spoof") || lower.contains("spoofed")) {
                                        spoofedUrl = downloadUrl;
                                        spoofedFileName = name;
                                    } else if (standardUrl == null) {
                                        standardUrl = downloadUrl;
                                        standardFileName = name;
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "GitHub API fetch failed", e);
                }
            }

            final String finalStandardUrl = standardUrl;
            final String finalStandardName = standardFileName != null ? standardFileName : selectedEntry.title.replaceAll("[^a-zA-Z0-9]", "_") + ".apk";
            final String finalSpoofedUrl = spoofedUrl;
            final String finalSpoofedName = spoofedFileName != null ? spoofedFileName : selectedEntry.title.replaceAll("[^a-zA-Z0-9]", "_") + "_spoof.apk";

            mMain.post(() -> {
                btnDownloadManager.setEnabled(true);

                if (finalSpoofedUrl != null && finalStandardUrl != null) {
                    new MaterialAlertDialogBuilder(MainActivity.this)
                            .setTitle("Choose " + selectedEntry.title + " Variant")
                            .setMessage("Both Spoofed and Standard release packages are available. Which version would you like to install?")
                            .setPositiveButton("Spoofed Version", (dialog, which) -> {
                                startDownload(finalSpoofedUrl, finalSpoofedName);
                            })
                            .setNegativeButton("Standard Release", (dialog, which) -> {
                                startDownload(finalStandardUrl, finalStandardName);
                            })
                            .setNeutralButton("Cancel", null)
                            .show();
                } else if (finalSpoofedUrl != null) {
                    startDownload(finalSpoofedUrl, finalSpoofedName);
                } else if (finalStandardUrl != null) {
                    startDownload(finalStandardUrl, finalStandardName);
                } else if (selectedEntry.fallbackWebUrl != null) {
                    statusView.setText("Redirecting to " + selectedEntry.title + " release page…");
                    try {
                        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(selectedEntry.fallbackWebUrl));
                        intent.addCategory(Intent.CATEGORY_BROWSABLE);
                        startActivity(Intent.createChooser(intent, "Download manager APK via browser"));
                    } catch (Exception e) {
                        Toast.makeText(MainActivity.this, "Unable to open link: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                } else {
                    Toast.makeText(MainActivity.this, "No download link available", Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private void startDownload(String downloadUrl, String fileName) {
        File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File existingFile = new File(downloadsDir, fileName);
        if (existingFile.exists() && existingFile.length() > 1000000) {
            Toast.makeText(this, fileName + " is already downloaded. Opening installer…", Toast.LENGTH_LONG).show();
            statusView.setText(fileName + " is already downloaded. Launching installer…");
            
            Uri apkUri = Uri.fromFile(existingFile);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    apkUri = FileProvider.getUriForFile(this, getPackageName() + ".provider", existingFile);
                } catch (Exception e) {
                    apkUri = Uri.fromFile(existingFile);
                }
            }
            Intent install = new Intent(Intent.ACTION_VIEW);
            install.setDataAndType(apkUri, "application/vnd.android.package-archive");
            install.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try {
                startActivity(install);
                return;
            } catch (Exception e) {
                Log.w(TAG, "Failed to open existing file: " + e.getMessage());
            }
        }

        try {
            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            if (dm != null) {
                DownloadManager.Request req = new DownloadManager.Request(Uri.parse(downloadUrl));
                req.setTitle("Downloading " + selectedEntry.title);
                req.setDescription(fileName);
                req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
                activeDownloadId = dm.enqueue(req);

                statusView.setText("Downloading " + fileName + "… Check notifications");
                Toast.makeText(MainActivity.this, "Download started directly in app!", Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) {
            Toast.makeText(MainActivity.this, "Download failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void copyLog() {
        ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Temporary Root log", outputView.getText()));
        Toast.makeText(this, "Log copied", Toast.LENGTH_SHORT).show();
    }

    private void shareLog() {
        startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, outputView.getText().toString()), "Share log"));
    }

    @Override public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    public static class ManagerEntry {
        public final String title;
        public final String description;
        public final String githubRepo;
        public final String fallbackWebUrl;
        public final String defaultPackageName;
        public final List<String> candidatePackages;

        public boolean isInstalled;
        public boolean hasKsud;
        public String installedPackageName;
        public CharSequence appLabel;
        public Drawable appIcon;
        public boolean isCustom;

        public ManagerEntry(String title, String description, String githubRepo, String fallbackWebUrl, String defaultPackageName, List<String> candidatePackages) {
            this.title = title;
            this.description = description;
            this.githubRepo = githubRepo;
            this.fallbackWebUrl = fallbackWebUrl;
            this.defaultPackageName = defaultPackageName;
            this.candidatePackages = candidatePackages;
            this.installedPackageName = defaultPackageName;
        }

        public String getEffectivePackageName() {
            return isInstalled ? installedPackageName : defaultPackageName;
        }

        @Override
        public String toString() {
            return title;
        }
    }

    private static class ManagerAdapter extends ArrayAdapter<ManagerEntry> {
        private final Context context;
        private final List<ManagerEntry> items;

        ManagerAdapter(Context context, List<ManagerEntry> items) {
            super(context, R.layout.item_su_manager_closed, items);
            this.context = context;
            this.items = items;
        }

        private static Drawable createBadgeBackground(int color) {
            GradientDrawable gd = new GradientDrawable();
            gd.setCornerRadius(10f);
            gd.setColor(color);
            return gd;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) {
                v = LayoutInflater.from(context).inflate(R.layout.item_su_manager_closed, parent, false);
            }
            ManagerEntry item = items.get(position);
            ImageView iconView = v.findViewById(R.id.iconApp);
            TextView titleView = v.findViewById(R.id.titleApp);
            TextView subtitleView = v.findViewById(R.id.subtitleApp);
            TextView badgeView = v.findViewById(R.id.statusBadge);

            titleView.setText(item.title);
            if (item.isInstalled) {
                if (item.appIcon != null) {
                    iconView.setImageDrawable(item.appIcon);
                    iconView.setVisibility(View.VISIBLE);
                } else {
                    iconView.setVisibility(View.GONE);
                }
                subtitleView.setText(item.installedPackageName);
                badgeView.setText("INSTALLED");
                badgeView.setTextColor(0xFF4ADE80);
                badgeView.setBackground(createBadgeBackground(0xFF143820));
            } else {
                iconView.setVisibility(View.GONE);
                subtitleView.setText(item.description);
                badgeView.setText("NOT INSTALLED");
                badgeView.setTextColor(0xFFFB923C);
                badgeView.setBackground(createBadgeBackground(0xFF3A2010));
            }
            return v;
        }

        @Override
        public View getDropDownView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) {
                v = LayoutInflater.from(context).inflate(R.layout.item_su_manager, parent, false);
            }
            ManagerEntry item = items.get(position);
            ImageView iconView = v.findViewById(R.id.iconApp);
            TextView titleView = v.findViewById(R.id.titleApp);
            TextView subtitleView = v.findViewById(R.id.subtitleApp);
            TextView packageView = v.findViewById(R.id.packageApp);
            TextView badgeView = v.findViewById(R.id.statusBadge);

            titleView.setText(item.title);
            subtitleView.setText(item.description);

            if (item.isInstalled) {
                if (item.appIcon != null) {
                    iconView.setImageDrawable(item.appIcon);
                    iconView.setVisibility(View.VISIBLE);
                } else {
                    iconView.setVisibility(View.GONE);
                }
                packageView.setText("Package: " + item.installedPackageName + (item.hasKsud ? "" : " (no libksud.so)"));
                badgeView.setText(item.hasKsud ? "INSTALLED" : "MISSING KSUD");
                badgeView.setTextColor(item.hasKsud ? 0xFF4ADE80 : 0xFFF87171);
                badgeView.setBackground(createBadgeBackground(item.hasKsud ? 0xFF143820 : 0xFF3F1D1D));
            } else {
                iconView.setVisibility(View.GONE);
                packageView.setText("Package: " + item.defaultPackageName);
                badgeView.setText("NOT INSTALLED");
                badgeView.setTextColor(0xFFFB923C);
                badgeView.setBackground(createBadgeBackground(0xFF3A2010));
            }
            return v;
        }
    }
}
