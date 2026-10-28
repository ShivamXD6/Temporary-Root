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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity implements IReporter {
    private static final String TAG = "temporaryroot";
    private Button btnRun, btnOpenManager, btnDownloadManager;
    private ScrollView outputScroll;
    private TextView outputView, statusView;
    private View statusDot;
    private Spinner spinnerSuManager, spinnerVulnerability;
    private View logContentContainer;
    private ImageView btnToggleLog;
    private boolean isLogExpanded = false;
    private boolean isExploitActive = false;
    private Context mDeCtx;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final Executor mExec = Executors.newSingleThreadExecutor();
    private final List<ManagerEntry> managers = new ArrayList<>();
    private final Map<String, Drawable> iconCache = new HashMap<>();
    private final List<VulnerabilityStrategy> strategies =
            Arrays.asList(new DirtyFragStrategy(), new GhostLockStrategy());
    private VulnerabilityStrategy strategy;
    private ManagerEntry selectedEntry;
    private String selectedManager;
    private long activeDownloadId = -1;
    private volatile boolean externalRoot = false;
    private volatile long externalRootCheckedAt = 0;
    private Boolean deviceOk;

    private final BroadcastReceiver downloadReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
            if (id == -1 || id != activeDownloadId) return;

            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            Uri apkUri = dm == null ? null : dm.getUriForDownloadedFile(id);
            if (apkUri == null) return;

            Toast.makeText(context, "Download complete. Launching installer…", Toast.LENGTH_LONG).show();
            if (ensureInstallPermission()) installApk(apkUri);
        }
    };

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    /** Returns false when install permission is missing; the settings screen is opened instead. */
    private boolean ensureInstallPermission() {
        if (getPackageManager().canRequestPackageInstalls()) return true;
        Toast.makeText(this, "Please enable 'Allow from this source' to install the manager", Toast.LENGTH_LONG).show();
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                    .setData(Uri.parse("package:" + getPackageName()))
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignored) {
        }
        return false;
    }

    private boolean installApk(Uri apkUri) {
        Intent install = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(apkUri, "application/vnd.android.package-archive")
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(install);
            return true;
        } catch (Exception e) {
            Toast.makeText(this, "Failed to launch installer: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    @Override public void report(String msg) {
        Log.i(TAG, msg.trim());
        RunLog.append(this, msg);
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
                v.setPadding(bars.left + dpToPx(20), bars.top + dpToPx(10), bars.right + dpToPx(20), bars.bottom + dpToPx(14));
                return insets;
            });
        }
        
        btnRun = findViewById(R.id.btnRun);
        btnOpenManager = findViewById(R.id.btnOpenManager);
        btnDownloadManager = findViewById(R.id.btnDownloadManager);
        outputScroll = findViewById(R.id.outputScroll);
        outputView = findViewById(R.id.outputView);
        statusView = findViewById(R.id.statusView);
        statusDot = findViewById(R.id.statusDot);
        spinnerSuManager = findViewById(R.id.spinnerSuManager);
        spinnerVulnerability = findViewById(R.id.spinnerVulnerability);
        logContentContainer = findViewById(R.id.logContentContainer);
        btnToggleLog = findViewById(R.id.btnToggleLog);
        View logHeader = findViewById(R.id.logHeader);

        if (outputScroll != null) {
            outputScroll.setOnTouchListener((v, event) -> {
                v.getParent().requestDisallowInterceptTouchEvent(true);
                int action = event.getAction() & MotionEvent.ACTION_MASK;
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    v.getParent().requestDisallowInterceptTouchEvent(false);
                    if (action == MotionEvent.ACTION_UP) v.performClick();
                }
                return false;
            });
        }

        View.OnClickListener toggleLogListener = v -> setLogExpanded(!isLogExpanded);
        if (logHeader != null) logHeader.setOnClickListener(toggleLogListener);
        if (btnToggleLog != null) btnToggleLog.setOnClickListener(toggleLogListener);

        String[] attackNames = new String[strategies.size()];
        for (int i = 0; i < strategies.size(); i++) attackNames[i] = strategies.get(i).name();
        strategy = strategies.get(0);
        spinnerVulnerability.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, attackNames));
        spinnerVulnerability.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                strategy = strategies.get(position);
                deviceOk = null;
                updateButtons();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        View btnVulnerabilityInfo = findViewById(R.id.btnVulnerabilityInfo);
        if (btnVulnerabilityInfo != null) {
            btnVulnerabilityInfo.setOnClickListener(v -> {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(strategy.infoUrl())));
                } catch (Exception e) {
                    Toast.makeText(this, "Unable to open link: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            });
        }

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
                deviceOk = null;
                updateButtons();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {
                selectedEntry = null;
                selectedManager = null;
                deviceOk = null;
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
        findViewById(R.id.btnShowLogs).setOnClickListener(v -> showSavedLog());
        btnOpenManager.setOnClickListener(v -> openManager());
        btnDownloadManager.setOnClickListener(v -> downloadManager());
        btnRun.setOnClickListener(v -> {
            btnRun.setEnabled(false);
            outputView.setText("");
            mExec.execute(this::runExploit);
        });

        IntentFilter downloadFilter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(downloadReceiver, downloadFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(downloadReceiver, downloadFilter);
        }

        updateButtons();

        // A previous run that never reached its end marker died mid-exploit; pull
        // the pstore trace while this process still has root.
        new Thread(() -> RunLog.capturePanicIfNeeded(this), "temporaryroot-panic").start();
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

    private void setLogExpanded(boolean expanded) {
        isLogExpanded = expanded;
        if (logContentContainer != null) logContentContainer.setVisibility(expanded ? View.VISIBLE : View.GONE);
        if (btnToggleLog != null) btnToggleLog.setImageResource(expanded ? R.drawable.ic_expand_less : R.drawable.ic_expand_more);
    }

    private List<ManagerEntry> createKnownManagers() {
        List<ManagerEntry> list = new ArrayList<>();

        // Samsung kernels only accept the diabl0w fork's ksud, so on a Samsung
        // device the other managers are hidden instead of offering a broken run.
        boolean isSamsung = Build.MANUFACTURER.equalsIgnoreCase("samsung")
                || Build.BRAND.equalsIgnoreCase("samsung");
        if (isSamsung) {
            list.add(new ManagerEntry(
                    "Samsung KernelSU",
                    "diabl0w fork · required for Samsung kernels",
                    "diabl0w/KernelSU",
                    "https://github.com/diabl0w/KernelSU/releases",
                    "me.weishu.kernelsu",
                    Collections.singletonList("me.weishu.kernelsu")
            ));
            return list;
        }

        // OriginSU is first, and the installed-first sort below is stable, so it
        // stays the default selection until something else is installed.
        list.add(new ManagerEntry(
                "OriginSU",
                "KernelSU fork · modern manager (recommended)",
                "samakshkambxj/OriginSU",
                "https://github.com/samakshkambxj/OriginSU/releases/latest",
                "com.originsu.manager",
                Collections.singletonList("com.originsu.manager")
        ));

        list.add(new ManagerEntry(
                "Official KernelSU",
                "Original project · standard manager",
                "tiann/KernelSU",
                "https://github.com/tiann/KernelSU/releases/latest",
                "me.weishu.kernelsu",
                Collections.singletonList("me.weishu.kernelsu")
        ));

        list.add(new ManagerEntry(
                "KernelSU-Next",
                "Extended kernel support and SUSFS integration",
                "KernelSU-Next/KernelSU-Next",
                "https://github.com/KernelSU-Next/KernelSU-Next/releases/latest",
                "com.rifs.kernelsu",
                Arrays.asList("com.rifs.kernelsu", "com.kernelsu.next")
        ));

        list.add(new ManagerEntry(
                "BakaSU",
                "Formerly ReSukiSU · multi-manager support",
                "Baka-SU/BakaSU",
                "https://github.com/Baka-SU/BakaSU/releases/latest",
                "com.baka.sukisu",
                Arrays.asList("com.baka.sukisu", "io.github.bakasu")
        ));

        list.add(new ManagerEntry(
                "SukiSU-Ultra",
                "KPM support and SUSFS management",
                "SukiSU-Ultra/SukiSU-Ultra",
                "https://github.com/SukiSU-Ultra/SukiSU-Ultra/releases/latest",
                "com.sukisu.ultra",
                Arrays.asList("com.sukisu.ultra", "com.sukisu.manager", "com.resukisu.manager")
        ));

        return list;
    }

    private Drawable iconFor(PackageManager pm, ApplicationInfo app) {
        Drawable cached = iconCache.get(app.packageName);
        if (cached != null) return cached;
        Drawable icon = pm.getApplicationIcon(app);
        iconCache.put(app.packageName, icon);
        return icon;
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
                    entry.appIcon = iconFor(pm, app);
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
                String label = pm.getApplicationLabel(app).toString().trim();
                // Spoofed builds ship a random package id, so package matching misses
                // them; adopt them into the known entry so the repo stays downloadable.
                ManagerEntry known = null;
                for (ManagerEntry entry : managers) {
                    if (!entry.isInstalled && entry.title.equalsIgnoreCase(label)) {
                        known = entry;
                        break;
                    }
                }
                if (known != null) {
                    known.isInstalled = true;
                    known.hasKsud = true;
                    known.installedPackageName = app.packageName;
                    known.appIcon = iconFor(pm, app);
                    claimedPackages.add(app.packageName);
                    continue;
                }
                ManagerEntry customEntry = new ManagerEntry(
                        label,
                        "Installed custom manager",
                        null,
                        null,
                        app.packageName,
                        Collections.singletonList(app.packageName)
                );
                customEntry.isInstalled = true;
                customEntry.hasKsud = true;
                customEntry.installedPackageName = app.packageName;
                customEntry.appIcon = iconFor(pm, app);
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
        if (isExploitActive) return true;
        if (new File("/dev/tr").exists() || new File("/dev/trm6").exists() || new File("/dev/trm5").exists()) {
            return true;
        }
        // su can block on a root prompt, so probe it off the UI thread and cache the answer.
        long now = System.currentTimeMillis();
        if (now - externalRootCheckedAt > 5000) {
            externalRootCheckedAt = now;
            Thread probe = new Thread(() -> {
                try {
                    Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "id"});
                    BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
                    String line = reader.readLine();
                    p.waitFor();
                    externalRoot = line != null && line.contains("uid=0");
                } catch (Exception e) {
                    externalRoot = false;
                }
                mMain.post(this::updateButtons);
            }, "temporaryroot-su-probe");
            probe.setDaemon(true);
            probe.start();
        }
        return externalRoot;
    }

    private void updateButtons() {
        boolean isInstalled = selectedEntry != null && selectedEntry.isInstalled && selectedEntry.hasKsud;
        boolean hasDownload = selectedEntry != null && (selectedEntry.githubRepo != null || selectedEntry.fallbackWebUrl != null);
        boolean isActive = isRootActive();

        boolean dotOk = isActive || Boolean.TRUE.equals(deviceOk) || (deviceOk == null && isInstalled);
        boolean canRun = isInstalled && (deviceOk == null || deviceOk);
        btnOpenManager.setEnabled(isInstalled);
        btnDownloadManager.setEnabled(hasDownload);
        if (statusDot != null) {
            statusDot.setBackgroundTintList(ColorStateList.valueOf(
                    getColor(dotOk ? R.color.badge_ok_text : R.color.badge_warn_text)));
        }

        if (isActive) {
            btnRun.setEnabled(false);
            btnRun.setText("Temporary Root Active");
            if (deviceOk == null) statusView.setText("Temporary Root Active • Reboot device to unload");
        } else {
            btnRun.setEnabled(canRun);
            btnRun.setText("Start Temporary Root");
            if (deviceOk != null) return;
            if (selectedEntry == null) {
                statusView.setText("Pick a root manager to continue");
            } else if (isInstalled) {
                statusView.setText("Ready • " + selectedEntry.title);
            } else if (selectedEntry.isInstalled && !selectedEntry.hasKsud) {
                statusView.setText(selectedEntry.title + " is installed but has no libksud.so — reinstall it");
            } else {
                statusView.setText(selectedEntry.title + " is not installed. Tap Download to get the APK.");
            }
        }
    }

    private void checkDevice() {
        outputView.setText("");
        statusView.setText("Checking device…");
        VulnerabilityStrategy selected = strategy;
        mExec.execute(() -> {
            CompatibilityResult result = selected.inspectRunningDevice(this, selectedManager, this);
            mMain.post(() -> {
                if (selected != strategy) return;
                setLogExpanded(true);
                outputView.append("\n" + result.asText() + "\n");
                deviceOk = result.ok;
                updateButtons();
                statusView.setText(result.title);
                // Expanding the console makes the log's follow-scroll drag this
                // ScrollView down, which would hide the verdict card.
                View scroll = findViewById(R.id.mainScroll);
                scroll.post(() -> scroll.scrollTo(0, 0));
            });
        });
    }

    private void runExploit() {
        RunLog.start(mDeCtx);
        try {
            int rc = strategy.launch(mDeCtx, this);
            if (rc == 0) {
                isExploitActive = true;
            } else {
                isExploitActive = false;
            }
            String msg = rc == 0 ? "Temporary Root: SUCCESS" : "Temporary Root: Error (code " + rc + ")";
            report("\n" + msg + "\n");
            if (rc == 0 && mDeCtx.getSharedPreferences(ExploitRunner.PREFS_NAME, MODE_PRIVATE).getBoolean(ExploitRunner.PREF_OPEN_MANAGER, true)) {
                mMain.postDelayed(this::openManager, 2500);
            }
            mMain.post(() -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show());
        } catch (Exception e) {
            isExploitActive = false;
            Log.e(TAG, "exploit exception", e);
            report("\nexception: " + e + "\n");
        } finally {
            RunLog.finish(mDeCtx);
            mMain.post(this::updateButtons);
        }
    }

    /** The on-disk log, so it is still there after a crash or kernel panic. */
    private void showSavedLog() {
        String text = RunLog.read(this);
        new MaterialAlertDialogBuilder(this)
                .setTitle("Saved log")
                .setMessage(text)
                .setPositiveButton("Copy", (dialog, which) -> {
                    ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE))
                            .setPrimaryClip(ClipData.newPlainText("Temporary Root log", text));
                    Toast.makeText(this, "Log copied", Toast.LENGTH_SHORT).show();
                })
                .setNeutralButton("Share", (dialog, which) -> startActivity(Intent.createChooser(
                        new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text),
                        "Share log")))
                .setNegativeButton("Close", null)
                .show();
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
        if (!ensureInstallPermission()) return;

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
                        int standardScore = -1;
                        int spoofedScore = -1;
                        if (assets != null) {
                            for (int i = 0; i < assets.length(); i++) {
                                JSONObject asset = assets.getJSONObject(i);
                                String name = asset.optString("name", "");
                                String downloadUrl = asset.optString("browser_download_url", "");
                                if (!name.endsWith(".apk") || downloadUrl.isEmpty()) continue;

                                int score = apkScore(name);
                                if (score < 0) continue;
                                String lower = name.toLowerCase();
                                if (lower.contains("spoof")) {
                                    if (score > spoofedScore) {
                                        spoofedScore = score;
                                        spoofedUrl = downloadUrl;
                                        spoofedFileName = name;
                                    }
                                } else if (score > standardScore) {
                                    standardScore = score;
                                    standardUrl = downloadUrl;
                                    standardFileName = name;
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

    /**
     * Ranks release APKs for this arm64-only build. Negative means unusable.
     * Releases often attach one APK per ABI (OriginSU does), and the first
     * asset in the list is not guaranteed to be the arm64 one.
     */
    private static int apkScore(String fileName) {
        String name = fileName.toLowerCase();
        if (name.contains("x86") || name.contains("riscv")) return -1;
        if (name.contains("universal")) return 3;
        if (name.contains("arm64")) return 2;
        if (name.contains("armeabi")) return 1;
        return 0;
    }

    private void startDownload(String downloadUrl, String fileName) {
        File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File existingFile = new File(downloadsDir, fileName);
        if (existingFile.exists() && existingFile.length() > 1000000) {
            Toast.makeText(this, fileName + " is already downloaded. Opening installer…", Toast.LENGTH_LONG).show();
            statusView.setText(fileName + " is already downloaded. Launching installer…");
            try {
                Uri apkUri = FileProvider.getUriForFile(this, getPackageName() + ".provider", existingFile);
                if (installApk(apkUri)) return;
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
        public Drawable appIcon;

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

        ManagerAdapter(Context context, List<ManagerEntry> items) {
            super(context, R.layout.item_su_manager_closed, items);
            this.context = context;
        }

        private Drawable createBadgeBackground(int colorRes) {
            GradientDrawable gd = new GradientDrawable();
            gd.setCornerRadius(100f * context.getResources().getDisplayMetrics().density);
            gd.setColor(context.getColor(colorRes));
            return gd;
        }

        private void bindIcon(View v, ManagerEntry item) {
            ImageView iconView = v.findViewById(R.id.iconApp);
            if (item.appIcon != null) {
                iconView.setImageDrawable(item.appIcon);
                iconView.setVisibility(View.VISIBLE);
            } else {
                iconView.setVisibility(View.GONE);
            }
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) {
                v = LayoutInflater.from(context).inflate(R.layout.item_su_manager_closed, parent, false);
            }
            ManagerEntry item = getItem(position);
            ((TextView) v.findViewById(R.id.titleApp)).setText(item.title);
            ((TextView) v.findViewById(R.id.subtitleApp))
                    .setText(item.isInstalled ? item.installedPackageName : item.description);
            bindIcon(v, item);
            return v;
        }

        @Override
        public View getDropDownView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) {
                v = LayoutInflater.from(context).inflate(R.layout.item_su_manager, parent, false);
            }
            ManagerEntry item = getItem(position);
            TextView titleView = v.findViewById(R.id.titleApp);
            TextView subtitleView = v.findViewById(R.id.subtitleApp);
            TextView packageView = v.findViewById(R.id.packageApp);
            TextView badgeView = v.findViewById(R.id.statusBadge);

            titleView.setText(item.title);
            subtitleView.setText(item.description);
            bindIcon(v, item);

            if (item.isInstalled) {
                packageView.setText("Package: " + item.installedPackageName + (item.hasKsud ? "" : " (no libksud.so)"));
                badgeView.setText(item.hasKsud ? "INSTALLED" : "MISSING KSUD");
                badgeView.setTextColor(context.getColor(item.hasKsud ? R.color.badge_ok_text : R.color.badge_bad_text));
                badgeView.setBackground(createBadgeBackground(item.hasKsud ? R.color.badge_ok_bg : R.color.badge_bad_bg));
            } else {
                packageView.setText("Package: " + item.defaultPackageName);
                badgeView.setText("NOT INSTALLED");
                badgeView.setTextColor(context.getColor(R.color.badge_warn_text));
                badgeView.setBackground(createBadgeBackground(R.color.badge_warn_bg));
            }
            return v;
        }
    }
}
