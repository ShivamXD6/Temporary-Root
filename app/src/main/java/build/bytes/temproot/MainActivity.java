package build.bytes.temproot;

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
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.RadioButton;
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
    private ProgressBar downloadProgress;
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
    private volatile boolean externalRoot = false;
    private volatile long externalRootCheckedAt = 0;
    private Boolean deviceOk;

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
            // Skip the share sheet when a system installer is present; third
            // party APK openers (MT Manager etc.) only confuse users here.
            for (String pkg : new String[]{"com.google.android.packageinstaller", "com.android.packageinstaller"}) {
                Intent direct = new Intent(install).setPackage(pkg);
                if (getPackageManager().resolveActivity(direct, 0) != null) {
                    startActivity(direct);
                    return true;
                }
            }
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
        downloadProgress = findViewById(R.id.downloadProgress);
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
                updateStrategyTips();
                updateButtons();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        updateStrategyTips();

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
                    if (selectedEntry.isInstalled) {
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

        updateButtons();

        // A previous run that never reached its end marker died mid-exploit; pull
        // the pstore trace while this process still has root.
        new Thread(() -> RunLog.capturePanicIfNeeded(this), "temporaryroot-panic").start();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadManagers();
    }

    /** GhostLock tips only make sense for that strategy. */
    private void updateStrategyTips() {
        View tips = findViewById(R.id.ghostlockTips);
        if (tips != null) tips.setVisibility(strategy instanceof GhostLockStrategy ? View.VISIBLE : View.GONE);
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
                    if (!entry.isInstalled && label.toLowerCase().contains(entry.title.toLowerCase())) {
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
        // A saved choice that is no longer installed (or was never installed)
        // must not leave the app pointing at a manager it cannot launch.
        if (!managers.isEmpty() && !managers.get(selectedIndex).isInstalled
                && managers.get(0).isInstalled) {
            selectedIndex = 0;
            spinnerSuManager.setSelection(0);
        }
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
        boolean isInstalled = selectedEntry != null && selectedEntry.isInstalled;
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
                report("\n=== device check ===\n" + result.asText() + "\n");
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
        // A frozen or throttled app ruins the exploit's timing, so hold the CPU
        // and the screen for the whole run (the core caps itself at ~5 minutes).
        PowerManager.WakeLock wake = ((PowerManager) getSystemService(POWER_SERVICE))
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "temproot:exploit");
        wake.acquire(15 * 60 * 1000L);
        mMain.post(() -> getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
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
            mMain.post(() -> getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
            wake.release();
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
                .setNeutralButton("Save", (dialog, which) -> {
                    RunLog.export(this);
                    Toast.makeText(this, "Saved to Download/TemporaryRoot/last-run.log", Toast.LENGTH_LONG).show();
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private void openManager() {
        String pkg = selectedManager;
        if (pkg == null && selectedEntry != null) pkg = selectedEntry.installedPackageName;
        if (pkg == null) return;
        Intent launch = getPackageManager().getLaunchIntentForPackage(pkg);
        if (launch == null) {
            Toast.makeText(this, "No launch activity for this manager", Toast.LENGTH_LONG).show();
        } else {
            startActivity(launch);
        }
    }

    /** One APK attached to the manager's latest GitHub release. */
    private static final class Variant {
        final String name, url;
        final long size;

        Variant(String name, String url, long size) {
            this.name = name;
            this.url = url;
            this.size = size;
        }
    }

    private void downloadManager() {
        if (selectedEntry == null) return;
        if (!ensureInstallPermission()) return;

        btnDownloadManager.setEnabled(false);
        statusView.setText("Fetching " + selectedEntry.title + " releases…");

        mExec.execute(() -> {
            List<Variant> variants = selectedEntry.githubRepo == null
                    ? new ArrayList<>() : fetchVariants(selectedEntry.githubRepo);
            mMain.post(() -> {
                btnDownloadManager.setEnabled(true);
                if (variants.isEmpty()) {
                    if (selectedEntry.fallbackWebUrl != null) {
                        statusView.setText("Opening " + selectedEntry.title + " releases…");
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
                    return;
                }
                if (hasSpoof(variants) && hasAbiBuild(variants)) showVariantPicker(variants);
                else startDownload(variants.get(recommendedIndex(variants)));
            });
        });
    }

    /** Every .apk asset of the latest release, in release order. */
    private static List<Variant> fetchVariants(String repo) {
        for (int attempt = 0; attempt < 3; attempt++) {
            List<Variant> out = fetchVariantsOnce(repo);
            if (out != null) return out;
            try {
                Thread.sleep(1000L << attempt);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return new ArrayList<>();
    }

    /** One attempt; null means a transient failure worth retrying. */
    private static List<Variant> fetchVariantsOnce(String repo) {
        List<Variant> out = new ArrayList<>();
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(
                    "https://api.github.com/repos/" + repo + "/releases/latest").openConnection();
            conn.setRequestProperty("User-Agent", "TemporaryRootApp");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            int code = conn.getResponseCode();
            if (code != 200) return code >= 500 || code == 429 ? null : out;

            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                for (String line; (line = reader.readLine()) != null; ) sb.append(line);
            }
            JSONArray assets = new JSONObject(sb.toString()).optJSONArray("assets");
            if (assets == null) return out;
            for (int i = 0; i < assets.length(); i++) {
                JSONObject asset = assets.getJSONObject(i);
                String name = asset.optString("name", "");
                String url = asset.optString("browser_download_url", "");
                if (name.isEmpty() || url.isEmpty() || !name.toLowerCase().endsWith(".apk")) continue;
                out.add(new Variant(name, url, asset.optLong("size", 0)));
            }
            return out;
        } catch (Exception e) {
            Log.w(TAG, "GitHub release fetch failed", e);
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private void showVariantPicker(List<Variant> variants) {
        int recommended = recommendedIndex(variants);
        // Put the recommended build first so the default choice is also the top one.
        List<Variant> ordered = new ArrayList<>(variants);
        ordered.add(0, ordered.remove(recommended));

        String abi = Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "arm64-v8a";
        final int[] choice = {0};
        VariantAdapter adapter = new VariantAdapter(ordered, abi, choice);

        ListView list = new ListView(this);
        list.setDivider(null);
        list.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        list.setAdapter(adapter);
        list.setItemChecked(0, true);
        list.setOnItemClickListener((parent, view, position, id) -> {
            choice[0] = position;
            adapter.notifyDataSetChanged();
        });

        new MaterialAlertDialogBuilder(this)
                .setTitle("Choose " + selectedEntry.title + " build")
                .setView(list)
                .setPositiveButton("Download", (dialog, which) -> startDownload(ordered.get(choice[0])))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private final class VariantAdapter extends BaseAdapter {
        private final List<Variant> items;
        private final String abi;
        private final int[] choice;

        VariantAdapter(List<Variant> items, String abi, int[] choice) {
            this.items = items;
            this.abi = abi;
            this.choice = choice;
        }

        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView != null ? convertView
                    : getLayoutInflater().inflate(R.layout.item_variant, parent, false);
            Variant v = items.get(position);
            ((TextView) row.findViewById(R.id.variantName)).setText(
                    describeVariant(v, abi) + (position == 0 ? "  •  Recommended" : ""));
            ((TextView) row.findViewById(R.id.variantMeta)).setText(
                    formatSize(v.size) + "  •  " + v.name);
            ((RadioButton) row.findViewById(R.id.variantRadio)).setChecked(choice[0] == position);
            return row;
        }
    }

    private static boolean hasSpoof(List<Variant> variants) {
        for (Variant v : variants) {
            if (v.name.toLowerCase().contains("spoof")) return true;
        }
        return false;
    }

    private static boolean hasAbiBuild(List<Variant> variants) {
        String abi = Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "arm64-v8a";
        for (Variant v : variants) {
            if (matchesAbi(v.name, abi)) return true;
        }
        return false;
    }

    /** Plain-English difference between otherwise similar filenames. */
    private static String describeVariant(Variant v, String abi) {
        String name = v.name.toLowerCase();
        if (name.contains("spoof")) return "Spoofed build";
        if (matchesAbi(v.name, abi)) return abi + " build";
        if (name.contains("universal")) return "Universal build";
        return "Standard build";
    }

    /**
     * Spoofed builds win because they ship a random package id; then the build
     * matching this device's ABI; then a universal one; then whatever the
     * release lists first.
     */
    private static int recommendedIndex(List<Variant> variants) {
        for (int i = 0; i < variants.size(); i++) {
            if (variants.get(i).name.toLowerCase().contains("spoof")) return i;
        }
        String abi = Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "arm64-v8a";
        for (int i = 0; i < variants.size(); i++) {
            if (matchesAbi(variants.get(i).name, abi)) return i;
        }
        for (int i = 0; i < variants.size(); i++) {
            if (variants.get(i).name.toLowerCase().contains("universal")) return i;
        }
        return 0;
    }

    private static boolean matchesAbi(String fileName, String abi) {
        String name = fileName.toLowerCase();
        if (abi.startsWith("arm64")) return name.contains("arm64") || name.contains("v8a");
        if (abi.startsWith("armeabi")) {
            return name.contains("armeabi") || (name.contains("arm") && !name.contains("arm64"));
        }
        return name.contains(abi.toLowerCase().replace('-', '_')) || name.contains(abi.toLowerCase());
    }

    private static String formatSize(long bytes) {
        if (bytes <= 0) return "size unknown";
        double mb = bytes / (1024.0 * 1024.0);
        return mb >= 1
                ? String.format(java.util.Locale.US, "%.1f MB", mb)
                : String.format(java.util.Locale.US, "%.0f KB", bytes / 1024.0);
    }

    /** APKs already in our download folder belong to a manager decided by filename. */
    private static final String[] MANAGER_KEYS = {
            "kernelsunext", "sukisuultra", "originsu", "bakasu", "kernelsu", "apatch", "magisk"
    };

    private static String normalizeName(String s) {
        return s.toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    /**
     * Reuse an APK already sitting in the download folder instead of fetching it
     * again, even when the release renamed the file. Returns null when nothing
     * clearly belongs to the selected manager.
     */
    private File alreadyDownloaded(File apkDir, Variant variant) {
        File exact = new File(apkDir, variant.name);
        if (exact.isFile() && exact.length() > 100000) return exact;

        String key = selectedEntry == null ? ""
                : normalizeName(selectedEntry.githubRepo != null
                        ? selectedEntry.githubRepo.substring(selectedEntry.githubRepo.lastIndexOf('/') + 1)
                        : selectedEntry.title);
        if (key.isEmpty()) return null;

        File best = null;
        File[] files = apkDir.listFiles();
        if (files == null) return null;
        for (File f : files) {
            if (!f.isFile() || !f.getName().toLowerCase().endsWith(".apk") || f.length() < 100000) continue;
            String name = normalizeName(f.getName());
            if (!name.contains(key)) continue;
            // "kernelsu" also matches "kernelsunext"; only accept the longest key.
            boolean wrongManager = false;
            for (String other : MANAGER_KEYS) {
                if (other.length() > key.length() && name.contains(other)) {
                    wrongManager = true;
                    break;
                }
            }
            if (wrongManager) continue;
            if (best == null || f.lastModified() > best.lastModified()) best = f;
        }
        return best;
    }

    private void startDownload(Variant variant) {
        File dir = getExternalFilesDir(null);
        if (dir == null) dir = getCacheDir();
        File apkDir = new File(dir, "apk");
        if (!apkDir.isDirectory() && !apkDir.mkdirs()) {
            Toast.makeText(this, "Cannot create download folder", Toast.LENGTH_SHORT).show();
            return;
        }
        File existing = alreadyDownloaded(apkDir, variant);
        if (existing != null) {
            statusView.setText(existing.getName() + " is already downloaded");
            launchInstaller(existing);
            return;
        }
        File target = new File(apkDir, variant.name);

        btnDownloadManager.setEnabled(false);
        downloadProgress.setVisibility(View.VISIBLE);
        downloadProgress.setProgress(0);
        statusView.setText("Downloading " + variant.name + "…");

        mExec.execute(() -> {
            File part = new File(apkDir, variant.name + ".part");
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(variant.url).openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(30000);
                conn.setRequestProperty("User-Agent", "TemporaryRootApp");
                conn.setRequestProperty("Accept-Encoding", "identity");
                conn.connect();
                long total = conn.getContentLengthLong();
                if (total <= 0) total = variant.size;

                try (java.io.InputStream in = new java.io.BufferedInputStream(conn.getInputStream(), 1 << 16);
                     java.io.FileOutputStream out = new java.io.FileOutputStream(part)) {
                    byte[] buf = new byte[1 << 16];
                    long done = 0;
                    int lastPct = -1, n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        done += n;
                        if (total > 0) {
                            int pct = (int) Math.min(100, done * 100 / total);
                            if (pct != lastPct) {
                                lastPct = pct;
                                final long d = done, t = total;
                                mMain.post(() -> showProgress(pct, d, t));
                            }
                        }
                    }
                }
                conn.disconnect();
                if (part.length() < 100000) throw new java.io.IOException("Download incomplete");

                if (target.exists() && !target.delete()) throw new java.io.IOException("Cannot replace old APK");
                if (!part.renameTo(target)) throw new java.io.IOException("Cannot save download");

                mMain.post(() -> {
                    downloadProgress.setVisibility(View.GONE);
                    btnDownloadManager.setEnabled(true);
                    statusView.setText("Downloaded " + variant.name);
                    launchInstaller(target);
                });
            } catch (Exception e) {
                Log.w(TAG, "Download failed: " + variant.name + " from " + variant.url, e);
                part.delete();
                mMain.post(() -> {
                    downloadProgress.setVisibility(View.GONE);
                    btnDownloadManager.setEnabled(true);
                    statusView.setText("Download failed");
                    Toast.makeText(MainActivity.this, "Download failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void showProgress(int pct, long done, long total) {
        downloadProgress.setProgress(pct);
        statusView.setText("Downloading " + pct + "%  •  "
                + String.format(java.util.Locale.US, "%.1f", done / (1024.0 * 1024.0)) + " / "
                + String.format(java.util.Locale.US, "%.1f", total / (1024.0 * 1024.0)) + " MB");
    }

    private void launchInstaller(File apk) {
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".provider", apk);
            if (ensureInstallPermission()) installApk(uri);
        } catch (Exception e) {
            Toast.makeText(this, "Cannot open downloaded APK: " + e.getMessage(), Toast.LENGTH_LONG).show();
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
