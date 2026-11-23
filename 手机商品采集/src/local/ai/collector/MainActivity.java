package local.ai.collector;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int GREEN = Color.rgb(20, 106, 85);
    private static final int INK = Color.rgb(29, 45, 39);
    private static final int MUTED = Color.rgb(94, 111, 102);
    private static final int BACKGROUND = Color.rgb(244, 247, 245);
    private static final int EXPORT_REQUEST = 91;
    private static final String[] KAOGU_DATE_RANGES = {"yesterday", "7d", "30d"};

    private interface PageFactory { PlatformPage create(); }

    private static final class PlatformRoute {
        final String label;
        final String settingsName;
        final PageFactory factory;
        PlatformRoute(String label, String settingsName, PageFactory factory) {
            this.label = label;
            this.settingsName = settingsName;
            this.factory = factory;
        }
    }

    private static final class PlatformPage {
        final LinearLayout view;
        final Runnable refresh;
        final Runnable settings;
        PlatformPage(LinearLayout view, Runnable refresh, Runnable settings) {
            this.view = view;
            this.refresh = refresh;
            this.settings = settings;
        }
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService workers = Executors.newSingleThreadExecutor();
    private final Map<String, PlatformRoute> platformRegistry = new LinkedHashMap<>();
    private final Map<String, PlatformPage> platformPages = new LinkedHashMap<>();
    private String activePlatformId = "douyin_mall";
    private TextView platformTitle;
    private LinearLayout platformPageHost;
    private ScrollView pageScroll;
    private LinearLayout capturePage;
    private LinearLayout resultPage;
    private LinearLayout resultRows;
    private TextView statusText;
    private TextView phaseText;
    private TextView progressText;
    private TextView permissionText;
    private TextView resultSummary;
    private TextView uploadMessage;
    private TextView batchStatusText;
    private TextView batchPhaseText;
    private TextView batchProgressText;
    private TextView batchTitleText;
    private Button captureTab;
    private Button resultTab;
    private Button pauseButton;
    private Button permissionButton;
    private Button batchStartButton;
    private Button batchPauseButton;
    private Button batchStopButton;
    private Button batchSkipButton;
    private Button retryFailedButton;
    private Button batchPreviewButton;
    private Button batchPermissionButton;
    private Button appBatchStartButton;
    private TextView workbenchStatus;
    private Button workbenchStandbyButton;
    private Button workbenchPairButton;
    private Spinner platformPicker;
    private TextView captureSettingsSummary;
    private Button singleStartButton;
    private Button singleStopButton;
    private Button uploadSettingsButton;
    private Button clearRecordsButton;
    private boolean clearingRecords;
    private LinearLayout uploadForm;
    private EditText serverUrl;
    private EditText authToken;
    private int showing = 20;
    private int currentTab;
    private long recordsVersion = -1;
    private String uploadVersion = "";

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            PlatformPage page = platformPages.get(activePlatformId);
            if (page != null) page.refresh.run();
            handler.postDelayed(this, 1000);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BACKGROUND);
        getWindow().setNavigationBarColor(BACKGROUND);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        ScrollView scroll = new ScrollView(this);
        pageScroll = scroll;
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BACKGROUND);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        LinearLayout content = vertical();
        content.setFocusable(true);
        content.setFocusableInTouchMode(true);
        content.setPadding(dp(18), dp(22), dp(18), dp(30));
        scroll.addView(content);
        platformRegistry.put("douyin_mall", new PlatformRoute("抖音", "collector_settings", this::createDouyinPage));
        platformTitle = text("抖音商品采集", 28, INK);
        platformTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        content.addView(platformTitle);
        label(content, "平台");
        LinearLayout platformRow = new LinearLayout(this);
        platformPicker = new Spinner(this);
        platformPicker.setContentDescription("当前平台：抖音");
        final String[] platformIds = platformRegistry.keySet().toArray(new String[0]);
        String[] platformNames = new String[platformIds.length];
        for (int index = 0; index < platformIds.length; index++) platformNames[index] = platformRegistry.get(platformIds[index]).label;
        ArrayAdapter<String> platforms = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, platformNames);
        platforms.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        platformPicker.setAdapter(platforms);
        platformPicker.setSelection(0);
        platformRow.addView(platformPicker, new LinearLayout.LayoutParams(0, dp(49), 1));
        Button settingsButton = button("采集设置", false, view -> {
            PlatformPage page = platformPages.get(activePlatformId);
            if (page != null) page.settings.run();
        });
        LinearLayout.LayoutParams settingsParams = new LinearLayout.LayoutParams(dp(110), dp(49));
        settingsParams.leftMargin = dp(9);
        platformRow.addView(settingsButton, settingsParams);
        content.addView(platformRow);
        platformPageHost = vertical();
        content.addView(platformPageHost);
        platformPicker.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < platformIds.length) showPlatformPage(platformIds[position]);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        showPlatformPage("douyin_mall");
        setContentView(scroll);
        content.requestFocus();
        scroll.post(() -> scroll.scrollTo(0, 0));
        openRequestedResults();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        openRequestedResults();
    }

    private void openRequestedResults() {
        Intent intent = getIntent();
        if (intent == null || !intent.getBooleanExtra("open_results", false)) return;
        showPlatformPage("douyin_mall");
        selectTab(1);
        intent.removeExtra("open_results");
        if (pageScroll != null) pageScroll.post(() -> pageScroll.scrollTo(0, 0));
    }

    private PlatformPage createDouyinPage() {
        LinearLayout content = vertical();
        LinearLayout tabs = new LinearLayout(this);
        tabs.setPadding(0, dp(18), 0, dp(14));
        captureTab = button("采集", true, view -> selectTab(0));
        resultTab = button("结果与回传", false, view -> selectTab(1));
        tabs.addView(captureTab, weighted());
        LinearLayout.LayoutParams right = weighted();
        right.leftMargin = dp(9);
        tabs.addView(resultTab, right);
        content.addView(tabs);
        capturePage = vertical();
        resultPage = vertical();
        content.addView(capturePage);
        content.addView(resultPage);
        buildCapture();
        buildResults();
        selectTab(0);
        return new PlatformPage(content, this::refreshDouyinPage, this::showCaptureSettings);
    }

    private void showPlatformPage(String id) {
        PlatformRoute route = platformRegistry.get(id);
        if (route == null) return;
        activePlatformId = id;
        PlatformPage page = platformPages.get(id);
        if (page == null) {
            page = route.factory.create();
            platformPages.put(id, page);
        }
        if (page.view.getParent() != platformPageHost) {
            platformPageHost.removeAllViews();
            platformPageHost.addView(page.view);
        }
        platformTitle.setText(route.label + "商品采集");
        platformPicker.setContentDescription("当前平台：" + route.label);
        page.refresh.run();
    }

    private void refreshDouyinPage() {
        if (currentTab == 0) {
            updateWorkbenchStatus();
            updateStatus();
            updateBatchStatus();
        }
        long version = getSharedPreferences("collector_records", MODE_PRIVATE).getLong("generation", 0);
        String lastUpload = getSharedPreferences("collector_upload", MODE_PRIVATE).getString("last_attempt_at", "");
        if (currentTab == 1 && (recordsVersion != version || !lastUpload.equals(uploadVersion))) {
            refreshResults();
            recordsVersion = version;
            uploadVersion = lastUpload;
        }
    }

    @Override protected void onResume() {
        super.onResume();
        handler.removeCallbacks(refresh);
        handler.post(refresh);
        WorkbenchClient.get(this).setForeground(true);
        RecordStore.scheduleUpload(this);
    }

    @Override protected void onPause() {
        handler.removeCallbacks(refresh);
        WorkbenchClient.get(this).setForeground(false);
        super.onPause();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(refresh);
        workers.shutdown();
        super.onDestroy();
    }

    private void buildCapture() {
        buildWorkbench();
        buildBatchCapture();
        LinearLayout task = card(capturePage);
        heading(task, "单件采集");
        statusText = text("待命", 21, GREEN);
        statusText.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        task.addView(statusText);
        phaseText = text("打开商品页后开始采集", 15, MUTED);
        task.addView(phaseText);
        progressText = text("已采集 0 个规格组合", 15, MUTED);
        task.addView(progressText);
        permissionText = text("", 14, MUTED);
        task.addView(permissionText);
        permissionButton = button("打开无障碍设置", false, view -> openAccessibility());
        task.addView(permissionButton, rowParams());
        task.addView(button("打开抖音商品 App", false, view -> openMall()), rowParams());
        singleStartButton = button("采集当前商品", true, view -> startCapture());
        task.addView(singleStartButton, rowParams());
        pauseButton = button("暂停采集", false, view -> {
            String status = stateValue("status", "idle");
            CollectorService.command(isPaused(status) ? "resume" : "pause");
            updateStatus();
        });
        task.addView(pauseButton, rowParams());
        singleStopButton = button("结束并保留结果", false, view -> {
            CollectorService.command("stop");
            updateStatus();
        });
        task.addView(singleStopButton, rowParams());

        TextView note = text("先在抖音打开目标商品，再点击“采集当前商品”。顶部“采集设置”用于下一次任务；请保持手机解锁。", 14, MUTED);
        note.setPadding(0, dp(10), 0, dp(4));
        task.addView(note);
    }

    private void buildWorkbench() {
        LinearLayout panel = card(capturePage);
        heading(panel, "工作台接单");
        workbenchStatus = text("", 14, MUTED);
        panel.addView(workbenchStatus);
        LinearLayout actions = new LinearLayout(this);
        workbenchPairButton = button("连接工作台", false, view -> showWorkbenchPairing());
        workbenchStandbyButton = button("开启待命", true, view -> {
            WorkbenchClient client = WorkbenchClient.get(this);
            client.setStandby(!client.isStandby());
            updateWorkbenchStatus();
        });
        actions.addView(workbenchPairButton, weighted());
        LinearLayout.LayoutParams right = weighted();
        right.leftMargin = dp(9);
        actions.addView(workbenchStandbyButton, right);
        panel.addView(actions, rowParams());
        updateWorkbenchStatus();
    }

    private void updateWorkbenchStatus() {
        WorkbenchClient client = WorkbenchClient.get(this);
        workbenchStatus.setText(client.statusMessage());
        workbenchPairButton.setText(client.paired() ? "重新配对" : "连接工作台");
        workbenchPairButton.setEnabled(!client.hasJob());
        workbenchStandbyButton.setEnabled(client.paired());
        workbenchStandbyButton.setText(client.isStandby() ? "退出待命" : "开启待命");
        if (client.isStandby()) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void showWorkbenchPairing() {
        WorkbenchClient client = WorkbenchClient.get(this);
        client.setStandby(false);
        WorkbenchConnection existing = client.connection();
        LinearLayout body = vertical();
        body.setPadding(dp(24), 0, dp(24), dp(8));
        label(body, "工作台网址");
        EditText address = input("https://工作台地址", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        if (existing != null) address.setText(existing.address);
        body.addView(address);
        label(body, "配对码");
        EditText code = input("使用网页生成的六位配对码", InputType.TYPE_CLASS_NUMBER);
        body.addView(code);
        label(body, "服务器证书 SHA-256（可选）");
        EditText fingerprint = input("仅在工作台提供指纹时填写", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        if (existing != null) fingerprint.setText(existing.fingerprint);
        body.addView(fingerprint);
        TextView feedback = text("", 14, MUTED);
        body.addView(feedback);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("连接工作台").setView(body)
                .setPositiveButton("配对", null).setNegativeButton("取消", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            view.setEnabled(false);
            feedback.setText("正在连接工作台…");
            client.pair(address.getText().toString(), fingerprint.getText().toString(), code.getText().toString(), error -> {
                if (!dialog.isShowing() || isDestroyed()) return;
                view.setEnabled(true);
                if (!error.isEmpty()) {
                    feedback.setText(error);
                    return;
                }
                dialog.dismiss();
                updateWorkbenchStatus();
                toast("配对成功，可以开启待命");
            });
        }));
        dialog.show();
    }

    private void buildBatchCapture() {
        LinearLayout batch = card(capturePage);
        heading(batch, "考古加 App 批量采集");
        batch.addView(text("从官方 App 当前筛选列表逐件采集抖音商品的规格、价格与热销标记。", 14, MUTED));
        captureSettingsSummary = text("", 14, GREEN);
        batch.addView(captureSettingsSummary);
        updateCaptureSettingsSummary();
        batch.addView(button("打开考古加 App", true, view -> openKaoguApp()), rowParams());
        appBatchStartButton = button("开始采集当前列表", true, view -> startKaoguAppBatch());
        batch.addView(appBatchStartButton, rowParams());
        batchStatusText = text("尚未读取商品名单", 21, GREEN);
        batchStatusText.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        batch.addView(batchStatusText);
        batchProgressText = text("", 15, INK);
        batch.addView(batchProgressText);
        batchPhaseText = text("在考古加 App 保留目标筛选列表，选择周期后开始。", 14, MUTED);
        batch.addView(batchPhaseText);
        batchTitleText = text("", 14, MUTED);
        batchTitleText.setMaxLines(2);
        batch.addView(batchTitleText);

        LinearLayout startActions = new LinearLayout(this);
        startActions.setPadding(0, dp(10), 0, 0);
        batchPreviewButton = button("预览商品名单", false, view -> showBatchItems());
        batchStartButton = button("继续当前批次", false, view -> startBatch());
        startActions.addView(batchPreviewButton, weighted());
        LinearLayout.LayoutParams startRight = weighted();
        startRight.leftMargin = dp(9);
        startActions.addView(batchStartButton, startRight);
        batch.addView(startActions);

        LinearLayout controls = new LinearLayout(this);
        controls.setPadding(0, dp(10), 0, 0);
        batchPauseButton = button("暂停批次", false, view -> {
            CollectorService.command("pause_batch");
            updateBatchStatus();
        });
        batchStopButton = button("结束批次", false, view -> {
            CollectorService.command("stop_batch");
            updateBatchStatus();
        });
        controls.addView(batchPauseButton, weighted());
        LinearLayout.LayoutParams stopRight = weighted();
        stopRight.leftMargin = dp(9);
        controls.addView(batchStopButton, stopRight);
        batch.addView(controls);
        batchSkipButton = button("跳过当前商品", false, view -> {
            EditText reason = input("例如：平台提示已达购买上限", InputType.TYPE_CLASS_TEXT);
            new AlertDialog.Builder(this).setTitle("跳过当前商品并继续？")
                    .setMessage("已采到的规格会保留，工作台会收到本件的处理结果。")
                    .setView(reason).setNegativeButton("取消", null)
                    .setPositiveButton("跳过并继续", (dialog, which) -> {
                        String message = reason.getText().toString().trim();
                        CollectorService.skipPausedBatchItem(message.isEmpty() ? "手动跳过当前商品" : message);
                    }).show();
        });
        batchSkipButton.setVisibility(View.GONE);
        batch.addView(batchSkipButton, rowParams());
        retryFailedButton = button("重试失败商品", true, view -> retryFailedProducts());
        retryFailedButton.setVisibility(View.GONE);
        batch.addView(retryFailedButton, rowParams());
        batchPermissionButton = button("开启无障碍服务", false, view -> openAccessibility());
        batch.addView(batchPermissionButton, rowParams());

        LinearLayout legacy = vertical();
        legacy.setVisibility(View.GONE);
        legacy.addView(text("保留之前的网页登录入口。默认采集使用上方的官方 App。", 13, MUTED));
        legacy.addView(button("打开旧网页登录与筛选", false, view -> KaoguActivity.open(this)), rowParams());
        Button legacyToggle = button("展开旧网页模式", false, null);
        legacyToggle.setOnClickListener(view -> {
            boolean expand = legacy.getVisibility() != View.VISIBLE;
            legacy.setVisibility(expand ? View.VISIBLE : View.GONE);
            legacyToggle.setText(expand ? "收起旧网页模式" : "展开旧网页模式");
        });
        batch.addView(legacyToggle, rowParams());
        batch.addView(legacy);
    }

    private void buildResults() {
        LinearLayout summary = card(resultPage);
        heading(summary, "采集记录");
        resultSummary = text("", 17, GREEN);
        summary.addView(resultSummary);
        uploadMessage = text("待配置回传地址", 14, MUTED);
        summary.addView(uploadMessage);
        LinearLayout recordActions = new LinearLayout(this);
        recordActions.setPadding(0, dp(10), 0, 0);
        recordActions.addView(button("刷新记录", false, view -> refreshResults()), weighted());
        LinearLayout.LayoutParams exportRight = weighted();
        exportRight.leftMargin = dp(9);
        recordActions.addView(button("导出 JSON", false, view -> exportRecords()), exportRight);
        summary.addView(recordActions);
        clearRecordsButton = button("清空采集记录", false, view -> confirmClearRecords());
        clearRecordsButton.setTextColor(Color.rgb(156, 57, 47));
        summary.addView(clearRecordsButton, rowParams());

        LinearLayout uploadActions = new LinearLayout(this);
        uploadActions.setPadding(0, dp(10), 0, 0);
        uploadActions.addView(button("立即回传", true, view -> uploadPending()), weighted());
        uploadSettingsButton = button("回传设置", false, view -> {
            boolean expand = uploadForm.getVisibility() != View.VISIBLE;
            if (expand) {
                serverUrl.setText(preferences().getString("server_url", ""));
                authToken.setText(preferences().getString("auth_token", ""));
            }
            uploadForm.setVisibility(expand ? View.VISIBLE : View.GONE);
            uploadSettingsButton.setText(expand ? "收起设置" : "回传设置");
        });
        LinearLayout.LayoutParams settingsRight = weighted();
        settingsRight.leftMargin = dp(9);
        uploadActions.addView(uploadSettingsButton, settingsRight);
        summary.addView(uploadActions);
        uploadForm = vertical();
        uploadForm.setVisibility(View.GONE);
        summary.addView(uploadForm);
        LinearLayout upload = uploadForm;
        label(upload, "回传接口地址");
        serverUrl = input("https://你的服务器/api/mobile/records", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        serverUrl.setText(preferences().getString("server_url", ""));
        upload.addView(serverUrl);
        label(upload, "回传密钥（可选）");
        authToken = input("填写网站提供的密钥", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        authToken.setText(preferences().getString("auth_token", ""));
        upload.addView(authToken);
        TextView retained = text("断网时记录保存在手机；已有图片也会保留，收到服务器接收确认后清理相应图片缓存。", 14, MUTED);
        upload.addView(retained);
        upload.addView(button("保存回传设置", false, view -> {
            if (saveUploadSettings()) {
                toast("回传设置已保存");
                RecordStore.scheduleUpload(this);
                refreshResults();
                uploadForm.setVisibility(View.GONE);
                uploadSettingsButton.setText("回传设置");
            }
        }), rowParams());
        resultRows = vertical();
        resultPage.addView(resultRows);
    }

    private void selectTab(int tab) {
        currentTab = tab;
        capturePage.setVisibility(tab == 0 ? View.VISIBLE : View.GONE);
        resultPage.setVisibility(tab == 1 ? View.VISIBLE : View.GONE);
        styleButton(captureTab, tab == 0);
        styleButton(resultTab, tab == 1);
        if (tab == 1) refreshResults();
        else {
            updateStatus();
            updateBatchStatus();
        }
    }

    private void uploadPending() {
        if (uploadForm.getVisibility() == View.VISIBLE && !saveUploadSettings()) {
            uploadForm.setVisibility(View.VISIBLE);
            uploadSettingsButton.setText("收起设置");
            return;
        }
        if (preferences().getString("server_url", "").trim().isEmpty() && !WorkbenchClient.get(this).paired()) {
            uploadForm.setVisibility(View.VISIBLE);
            uploadSettingsButton.setText("收起设置");
            uploadMessage.setText("请先填写并保存网站回传地址");
            return;
        }
        uploadMessage.setText("正在尝试回传…");
        workers.execute(() -> {
            RecordStore.UploadSummary result = RecordStore.uploadPending(getApplicationContext());
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                refreshResults();
                uploadMessage.setText(result.message);
            });
        });
    }

    private void startBatch() {
        if (!saveCaptureSettings() || !requireAccessibility()) return;
        if (!CollectorService.startBatch(this)) toast("暂时无法启动批次，请查看批次提示");
        else if ("kaogujia_app".equals(BatchStore.settings(this).optString("source_mode"))) openKaoguApp();
        updateBatchStatus();
    }

    private void retryFailedProducts() {
        try {
            JSONObject summary = BatchStore.summary(this);
            if (!"complete".equals(summary.optString("status")) || summary.optInt("failed", 0) <= 0) {
                toast("只有已结束批次中的失败商品可以重试");
                updateBatchStatus();
                return;
            }
            if (!requireAccessibility()) return;
            int count = BatchStore.retryFailed(this);
            if (count <= 0) toast("没有需要重试的失败商品");
            else if (CollectorService.startBatch(this)) toast("开始重试 " + count + " 件失败商品");
            else toast("失败商品已保留，请点击继续当前批次");
        } catch (RuntimeException error) {
            toast(error.getMessage() == null ? "暂时无法重试，已有记录已保留" : error.getMessage());
        }
        updateBatchStatus();
    }

    private void startKaoguAppBatch() {
        if (!saveCaptureSettings()) return;
        int limit = preferences().getInt("kaogu_app_limit", 5);
        String range = preferences().getString("kaogu_app_date_range", "30d");
        if (!requireAccessibility()) return;
        String category = preferences().getString("kaogu_app_category", KaoguAppAdapter.PERSONAL_CARE_PATH);
        if (!CollectorService.startKaoguAppBatch(this, range, limit, category)) toast("暂时无法启动，请先处理当前任务并查看采集提示");
        else openKaoguApp();
        updateBatchStatus();
    }

    private boolean requireAccessibility() {
        if (CollectorService.isConnected()) return true;
        new AlertDialog.Builder(this).setTitle("开启无障碍服务")
                .setMessage("请在系统设置中开启“手机商品采集”，返回后开始采集。")
                .setPositiveButton("前往设置", (dialog, which) -> openAccessibility())
                .setNegativeButton("返回", null).show();
        return false;
    }

    private void startCapture() {
        if (!saveCaptureSettings()) return;
        if (!requireAccessibility()) return;
        if (!CollectorService.startTask(this)) toast("当前任务正在运行，请先暂停或结束");
        updateStatus();
    }

    private void openAccessibility() {
        startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
    }

    private void openMall() {
        Intent intent = getPackageManager().getLaunchIntentForPackage("com.ss.android.ugc.livelite");
        if (intent == null) intent = getPackageManager().getLaunchIntentForPackage("com.ss.android.ugc.aweme");
        if (intent == null) {
            toast("请先安装抖音商城或抖音 App");
            return;
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        startActivity(intent);
    }

    private void openKaoguApp() {
        Intent intent = getPackageManager().getLaunchIntentForPackage("com.fz.kaogujia");
        if (intent == null) {
            toast("请先安装官方考古加 App");
            return;
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            startActivity(intent);
        } catch (android.content.ActivityNotFoundException | SecurityException error) {
            toast("暂时无法打开考古加 App，请在手机上手动打开");
        }
    }

    private boolean saveCaptureSettings() {
        String range = preferences().getString("kaogu_app_date_range", "30d");
        boolean validRange = false;
        for (String option : KAOGU_DATE_RANGES) if (option.equals(range)) validRange = true;
        if (!validRange || preferences().getInt("kaogu_app_limit", 5) <= 0
                || preferences().getInt("max_combinations", 24) <= 0) {
            toast("请先保存有效的采集设置");
            showCaptureSettings();
            return false;
        }
        preferences().edit().putString("platform", "douyin_mall").apply();
        return true;
    }

    private void showCaptureSettings() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = vertical();
        body.setPadding(dp(22), dp(8), dp(22), dp(16));
        body.setFocusableInTouchMode(true);
        scroll.addView(body);
        body.addView(text("设置用于下一次任务。保存设置不会重新启动或暂停当前任务。", 14, GREEN));
        label(body, "考古加商品类目");
        String[] category = {preferences().getString("kaogu_app_category", KaoguAppAdapter.PERSONAL_CARE_PATH)};
        Button categoryButton = button(categoryDisplay(category[0]), false, view ->
                showKaoguCategories(path -> {
                    category[0] = path;
                    ((Button) view).setText(categoryDisplay(path));
                }));
        body.addView(categoryButton);
        label(body, "数据周期");
        Spinner dates = new Spinner(this);
        ArrayAdapter<String> options = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item,
                new String[]{"昨日", "7日", "30日"});
        options.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        dates.setAdapter(options);
        String savedRange = preferences().getString("kaogu_app_date_range", "30d");
        int selection = 2;
        for (int index = 0; index < KAOGU_DATE_RANGES.length; index++) if (KAOGU_DATE_RANGES[index].equals(savedRange)) selection = index;
        dates.setSelection(selection);
        body.addView(dates, new LinearLayout.LayoutParams(-1, dp(49)));
        label(body, "本次商品数");
        EditText count = input("填写大于零的整数", InputType.TYPE_CLASS_NUMBER);
        count.setText(String.valueOf(preferences().getInt("kaogu_app_limit", 5)));
        body.addView(count);
        label(body, "单件规格组合上限");
        EditText maximum = input("例如 24", InputType.TYPE_CLASS_NUMBER);
        maximum.setText(String.valueOf(preferences().getInt("max_combinations", 24)));
        body.addView(maximum);
        body.addView(text("图片由工作台根据抖音商品链接获取。", 13, MUTED));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("抖音采集设置").setView(scroll)
                .setNegativeButton("取消", null).setPositiveButton("保存", null).create();
        dialog.setOnShowListener(ignored -> {
            body.requestFocus();
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                Integer limit = positiveInteger(count, "请填写大于零的商品数");
                Integer combinations = positiveInteger(maximum, "请填写大于零的规格组合上限");
                if (limit == null || combinations == null) return;
                int position = dates.getSelectedItemPosition();
                String range = KAOGU_DATE_RANGES[position >= 0 && position < KAOGU_DATE_RANGES.length ? position : 2];
                preferences().edit().putString("platform", "douyin_mall")
                        .putString("kaogu_app_category", category[0])
                        .putString("kaogu_app_date_range", range).putInt("kaogu_app_limit", limit)
                        .putInt("max_combinations", combinations).apply();
                updateCaptureSettingsSummary();
                toast("设置已保存，用于下一次任务");
                dialog.dismiss();
            });
        });
        dialog.show();
    }

    private String categoryDisplay(String path) {
        if (path.isEmpty()) return "沿用商品库当前类目";
        java.util.List<String> names = KaoguCategories.path(this, path);
        return names.isEmpty() ? path : String.join(" › ", names);
    }

    private void showKaoguCategories(java.util.function.Consumer<String> select) {
        showKaoguCategoryLevel(KaoguCategories.tree(this), "", select, null);
    }

    private void showKaoguCategoryLevel(java.util.List<KaoguCategories.Category> categories, String path,
            java.util.function.Consumer<String> select, Runnable back) {
        boolean root = path.isEmpty();
        int builtin = root ? 2 : 1;
        String[] options = new String[categories.size() + builtin];
        options[0] = root ? KaoguCategories.ALL : "选择本级（全部子类目）";
        if (root) options[1] = "沿用商品库当前类目";
        for (int i = 0; i < categories.size(); i++) options[builtin + i] = categories.get(i).name;
        AlertDialog.Builder picker = new AlertDialog.Builder(this)
                .setTitle(root ? "选择考古加商品类目" : categoryDisplay(path))
                .setItems(options, (dialog, option) -> {
                    if (option == 0) { select.accept(root ? KaoguCategories.ALL : path); return; }
                    if (root && option == 1) { select.accept(""); return; }
                    KaoguCategories.Category chosen = categories.get(option - builtin);
                    String next = root ? chosen.name : path + "-" + chosen.name;
                    if (chosen.children.isEmpty()) select.accept(next);
                    else showKaoguCategoryLevel(chosen.children, next, select,
                            () -> showKaoguCategoryLevel(categories, path, select, back));
                });
        if (back != null) picker.setNegativeButton("返回", (dialog, option) -> back.run());
        else picker.setNegativeButton("取消", null);
        picker.show();
    }

    private Integer positiveInteger(EditText field, String message) {
        try {
            int value = Integer.parseInt(field.getText().toString().trim());
            if (value <= 0) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException error) {
            field.setError(message);
            return null;
        }
    }

    private void updateCaptureSettingsSummary() {
        if (captureSettingsSummary == null) return;
        String range = preferences().getString("kaogu_app_date_range", "30d");
        String label = range.equals("yesterday") ? "昨日" : range.equals("7d") ? "7日" : "30日";
        String category = preferences().getString("kaogu_app_category", KaoguAppAdapter.PERSONAL_CARE_PATH);
        captureSettingsSummary.setText("下次任务：" + categoryDisplay(category)
                + " · " + label + " · " + preferences().getInt("kaogu_app_limit", 5)
                + "件 · 每件最多" + preferences().getInt("max_combinations", 24) + "个规格组合");
    }

    private boolean saveUploadSettings() {
        String address = serverUrl.getText().toString().trim();
        if (!address.isEmpty()) {
            Uri uri = Uri.parse(address);
            if ((!"https".equals(uri.getScheme()) && !"http".equals(uri.getScheme())) || uri.getHost() == null) {
                serverUrl.setError("请填写完整的 http:// 或 https:// 接口地址");
                return false;
            }
        }
        preferences().edit().putString("server_url", address)
                .putString("auth_token", authToken.getText().toString().trim()).apply();
        return true;
    }

    private void updateStatus() {
        String status = stateValue("status", "idle");
        statusText.setText(statusName(status));
        phaseText.setText(stateValue("phase", "打开商品页后开始采集"));
        String progress = stateValue("progress", "0");
        progressText.setText("已采集 " + progress + (progress.contains("组合") ? "" : " 个规格组合"));
        boolean connected = CollectorService.isConnected();
        permissionText.setText(connected ? "无障碍服务已连接" : "请开启无障碍服务");
        permissionButton.setVisibility(connected ? View.GONE : View.VISIBLE);
        pauseButton.setText(isPaused(status) ? "继续当前任务" : "暂停采集");
        boolean active = status.equals("running") || status.equals("collecting") || status.equals("paused")
                || status.equals("waiting") || status.contains("暂停") || status.contains("采集中");
        pauseButton.setEnabled(active);
    }

    private void updateBatchStatus() {
        updateCaptureSettingsSummary();
        JSONObject summary;
        try {
            summary = BatchStore.summary(this);
        } catch (IllegalStateException error) {
            batchStatusText.setText("批次进度暂时无法读取");
            batchProgressText.setText("");
            batchPhaseText.setText("请保留应用数据。" + (error.getMessage() == null ? "" : error.getMessage()));
            batchTitleText.setVisibility(View.GONE);
            batchStartButton.setEnabled(false);
            appBatchStartButton.setEnabled(false);
            batchPauseButton.setEnabled(false);
            batchStopButton.setEnabled(false);
            batchSkipButton.setVisibility(View.GONE);
            retryFailedButton.setVisibility(View.GONE);
            retryFailedButton.setEnabled(false);
            batchPreviewButton.setEnabled(false);
            batchPermissionButton.setVisibility(CollectorService.isConnected() ? View.GONE : View.VISIBLE);
            singleStartButton.setEnabled(false);
            singleStopButton.setEnabled(false);
            pauseButton.setEnabled(false);
            return;
        }
        String status = summary.optString("status", "idle");
        int total = Math.max(0, summary.optInt("total", 0));
        int completed = Math.max(0, summary.optInt("completed", 0));
        int failed = Math.max(0, summary.optInt("failed", 0));
        boolean running = isBatchRunning(status);
        boolean finished = status.equals("complete") || status.equals("completed");
        boolean stopped = status.equals("stopped");
        boolean managed = !summary.optString("workbench_job_id").isEmpty();
        boolean retryAvailable = status.equals("complete") && failed > 0 && !managed;
        retryFailedButton.setVisibility(retryAvailable ? View.VISIBLE : View.GONE);
        retryFailedButton.setEnabled(retryAvailable);
        batchStatusText.setText(total == 0 && !running && !isPaused(status) ? "准备采集当前列表" : batchStatusName(status));
        batchProgressText.setText(total == 0 ? "" : "共 " + total + " 件 · 已完成 " + completed + " 件 · 失败 " + failed + " 件");
        String phase = summary.optString("phase", "");
        batchPhaseText.setText(phase.isEmpty() || (total == 0 && status.equals("idle"))
                ? "在考古加 App 保留目标筛选列表，选择周期后开始。" : phase);
        String title = summary.optString("title", "");
        int index = summary.optInt("index", -1);
        batchTitleText.setText(title.isEmpty() ? "" : (index >= 0 ? "当前第 " + Math.min(index + 1, Math.max(total, 1)) + " 件：" : "当前：") + title);
        batchTitleText.setVisibility(title.isEmpty() ? View.GONE : View.VISIBLE);
        batchPreviewButton.setEnabled(total > 0);
        batchStartButton.setText(running ? "正在采集" : finished ? "批次已完成" : stopped ? "批次已结束" : status.equals("ready") ? "开始已有批次" : "继续当前批次");
        batchStartButton.setEnabled((total > 0 || isPaused(status)) && !running && !finished && !stopped);
        batchPauseButton.setEnabled(running);
        batchStopButton.setEnabled(!finished && (running || isPaused(status) || status.equals("waiting") || status.equals("needs_user")));
        batchSkipButton.setVisibility(status.equals("paused") && index >= 0 && index < total
                && !summary.optBoolean("stop_requested") && CollectorService.isConnected() ? View.VISIBLE : View.GONE);
        batchPermissionButton.setVisibility(CollectorService.isConnected() ? View.GONE : View.VISIBLE);
        boolean batchOwnsTask = running || isPaused(status) || status.equals("waiting") || status.equals("needs_user");
        boolean workbenchOwnsTask = WorkbenchClient.get(this).hasJob();
        appBatchStartButton.setEnabled(!batchOwnsTask && !workbenchOwnsTask);
        singleStartButton.setEnabled(!batchOwnsTask && !workbenchOwnsTask);
        singleStopButton.setEnabled(!batchOwnsTask);
        if (batchOwnsTask) pauseButton.setEnabled(false);
    }

    private void showBatchItems() {
        JSONArray items;
        try {
            items = BatchStore.items(this);
        } catch (IllegalStateException error) {
            toast("暂时无法读取名单，请保留应用数据");
            return;
        }
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = vertical();
        body.setPadding(dp(22), dp(6), dp(22), dp(16));
        scroll.addView(body);
        if (items.length() == 0) body.addView(text("尚未读取商品名单。先在官方考古加 App 打开目标筛选列表，再开始采集。", 15, MUTED));
        for (int index = 0; index < items.length(); index++) {
            JSONObject item = items.optJSONObject(index);
            if (item == null) continue;
            String title = firstNonempty(item, "title", "product_name", "name");
            TextView name = text((index + 1) + ". " + (title.isEmpty() ? "未命名商品" : title), 16, INK);
            name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            name.setPadding(0, dp(14), 0, dp(4));
            body.addView(name);
            String shop = firstNonempty(item, "shop_name", "shop", "store_name");
            String product = firstNonempty(item, "product_id", "goods_id", "id");
            if (!shop.isEmpty()) body.addView(text("店铺：" + shop, 14, MUTED));
            if (!product.isEmpty()) body.addView(text("商品 ID：" + product, 13, MUTED));
            body.addView(text(batchItemStatusName(item.optString("status", "pending")), 14, GREEN));
            String reason = firstNonempty(item, "error", "phase", "message");
            if (!reason.isEmpty()) body.addView(text(reason, 13, MUTED));
        }
        new AlertDialog.Builder(this).setTitle("本批次商品名单 · " + items.length() + " 件")
                .setView(scroll).setPositiveButton("关闭", null).show();
    }

    private static String firstNonempty(JSONObject item, String... keys) {
        for (String key : keys) {
            Object value = item.opt(key);
            if (value == null || value == JSONObject.NULL || value instanceof JSONObject || value instanceof JSONArray) continue;
            String text = String.valueOf(value).trim();
            if (!text.isEmpty()) return text;
        }
        return "";
    }

    private static boolean isBatchRunning(String status) {
        return status.equals("running") || status.equals("collecting") || status.equals("starting")
                || status.equals("opening") || status.equals("preparing");
    }

    private static String batchStatusName(String status) {
        if (isBatchRunning(status)) return "批量采集中";
        if (status.equals("ready") || status.equals("idle") || status.equals("pending")) return "商品名单已就绪";
        if (status.equals("needs_login")) return "请先登录考古加";
        if (status.equals("stopped")) return "批次已结束";
        return statusName(status);
    }

    private static String batchItemStatusName(String status) {
        if (status.equals("pending") || status.equals("ready") || status.equals("idle")) return "待采集";
        if (status.equals("complete") || status.equals("completed") || status.equals("done") || status.equals("success")) return "已采集";
        if (status.equals("failed") || status.equals("error")) return "采集失败";
        if (status.equals("skipped")) return "已跳过";
        if (status.equals("fetching_qr")) return "正在获取二维码";
        if (status.equals("qr_ready")) return "二维码已就绪";
        if (status.equals("scanning")) return "正在扫码打开商品";
        if (isBatchRunning(status)) return "正在采集";
        return statusName(status);
    }

    private void refreshResults() {
        JSONArray records = RecordStore.records(this);
        clearRecordsButton.setEnabled(!clearingRecords && records.length() > 0);
        int pending = RecordStore.pendingCount(this);
        resultSummary.setText(records.length() + " 条记录 · " + pending + " 条待回传\n"
                + byteSize(RecordStore.pendingBytes(this)) + " 待上传缓存");
        String endpoint = preferences().getString("server_url", "").trim();
        String lastMessage = getSharedPreferences("collector_upload", MODE_PRIVATE).getString("last_message", "");
        boolean configured = !endpoint.isEmpty() || WorkbenchClient.get(this).paired();
        uploadMessage.setText(!configured ? "待配置回传地址" : lastMessage.isEmpty() ? "已配置回传地址" : lastMessage);
        resultRows.removeAllViews();
        if (records.length() == 0) {
            LinearLayout empty = card(resultRows);
            empty.addView(text("采集完成后，结果会保存在这里。", 15, MUTED));
            return;
        }
        for (int index = 0; index < Math.min(showing, records.length()); index++) {
            JSONObject record = records.optJSONObject(index);
            LinearLayout row = card(resultRows);
            TextView title = text(record.optString("title", "当前商品"), 17, INK);
            title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            row.addView(title);
            addRecordStatus(row, record);
            JSONArray skus = record.optJSONArray("skus");
            String state = record.optBoolean("uploaded", false) ? "服务器已确认接收" : "待回传";
            row.addView(text("抖音 · " + (skus == null ? 0 : skus.length()) + " 个规格组合 · " + state, 14, MUTED));
            row.addView(text(record.optString("saved_at", ""), 13, MUTED));
            LinearLayout actions = new LinearLayout(this);
            actions.setPadding(0, dp(10), 0, 0);
            actions.addView(button("查看记录", false, view -> showRecord(record)), weighted());
            Button remove = button("删除记录", false, view -> confirmDelete(record));
            remove.setTextColor(Color.rgb(156, 57, 47));
            LinearLayout.LayoutParams deleteParams = weighted();
            deleteParams.leftMargin = dp(9);
            actions.addView(remove, deleteParams);
            row.addView(actions);
        }
        if (records.length() > showing) {
            resultRows.addView(button("加载更多记录", false, view -> {
                showing += 20;
                refreshResults();
            }), rowParams());
        }
    }

    private void addRecordStatus(LinearLayout parent, JSONObject record) {
        String status = record.optString("status", "");
        String label;
        String reason = "";
        int foreground;
        int background;
        if ("invalid".equals(record.optString("product_state", ""))) {
            label = "已失效";
            reason = firstNonempty(record, "invalid_reason", "collection_error");
            foreground = Color.rgb(168, 47, 39);
            background = Color.rgb(255, 236, 232);
        } else if ("failed".equals(status)) {
            label = "采集失败（可重试）";
            reason = firstNonempty(record, "collection_error", "error");
            foreground = Color.rgb(156, 82, 18);
            background = Color.rgb(255, 244, 224);
        } else if ("partial".equals(status)) {
            label = "部分完成";
            reason = firstNonempty(record, "coverage_note", "collection_error");
            foreground = Color.rgb(156, 82, 18);
            background = Color.rgb(255, 244, 224);
        } else if ("complete".equals(status) || "completed".equals(status)) {
            label = "采集完成";
            foreground = GREEN;
            background = Color.rgb(232, 242, 236);
        } else {
            label = "stopped".equals(status) ? "任务已结束" : "待核对";
            foreground = MUTED;
            background = BACKGROUND;
        }
        TextView badge = text(label, 14, foreground);
        badge.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        badge.setPadding(dp(9), dp(4), dp(9), dp(4));
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(background);
        shape.setCornerRadius(dp(6));
        badge.setBackground(shape);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
        params.topMargin = dp(7);
        params.bottomMargin = dp(4);
        parent.addView(badge, params);
        if (!reason.isEmpty()) parent.addView(text(reason, 14, foreground));
    }

    private void confirmClearRecords() {
        if (clearingRecords) return;
        JSONArray records = RecordStore.records(this);
        java.util.ArrayList<String> ids = new java.util.ArrayList<>();
        for (int i = 0; i < records.length(); i++) ids.add(records.optJSONObject(i).optString("record_id"));
        if (ids.isEmpty()) return;
        new AlertDialog.Builder(this).setTitle("清空手机采集记录？")
                .setMessage("将删除当前手机保存的 " + ids.size() + " 条采集记录，包括待回传记录和关联图片。正在处理的记录会保留，已入库的数据仍可在工作台查看。删除后无法恢复。")
                .setNegativeButton("取消", null).setPositiveButton("清空", (dialog, which) -> {
                    clearingRecords = true;
                    clearRecordsButton.setEnabled(false);
                    workers.execute(() -> {
                        String message;
                        try {
                            RecordStore.DeleteSummary result = RecordStore.deleteSavedRecords(getApplicationContext(), ids);
                            message = "已删除 " + result.deleted + " 条"
                                    + (result.active == 0 ? "" : "，保留 " + result.active + " 条进行中记录")
                                    + (result.failed == 0 ? "" : "，" + result.failed + " 条删除失败");
                        } catch (RuntimeException error) {
                            message = error.getMessage() == null ? "暂时无法清空记录" : error.getMessage();
                        }
                        String outcome = message;
                        runOnUiThread(() -> {
                            if (isDestroyed()) return;
                            clearingRecords = false;
                            showing = 20;
                            refreshResults();
                            toast(outcome);
                        });
                    });
                }).show();
    }

    private void confirmDelete(JSONObject record) {
        String title = firstNonempty(record, "title", "source_title");
        if (title.isEmpty()) title = "未命名商品";
        String shop = record.optString("shop_name", "");
        String message = "商品：" + title + (shop.isEmpty() ? "" : "\n店铺：" + shop)
                + "\n\n删除手机保存的这条记录，并清理没有其他记录引用的图片缓存。进行中的任务记录不能删除。";
        String recordId = record.optString("record_id", "");
        new AlertDialog.Builder(this).setTitle("删除这条采集记录？").setMessage(message)
                .setNegativeButton("取消", null).setPositiveButton("删除", (dialog, which) -> workers.execute(() -> {
                    String result;
                    try {
                        result = RecordStore.delete(getApplicationContext(), recordId) ? "记录已删除" : "记录已不存在";
                    } catch (RuntimeException error) {
                        result = error.getMessage() == null ? "暂时无法删除，记录已保留" : error.getMessage();
                    }
                    String outcome = result;
                    runOnUiThread(() -> {
                        if (isDestroyed()) return;
                        refreshResults();
                        toast(outcome);
                    });
                })).show();
    }

    private void showRecord(JSONObject record) {
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = vertical();
        body.setPadding(dp(22), dp(8), dp(22), dp(12));
        scroll.addView(body);
        addRecordStatus(body, record);
        addProductNames(body, record);
        String productLink = ProductLinkActivity.productLink(firstNonempty(record, "douyin_share_url", "source_url"));
        if (!productLink.isEmpty()) {
            body.addView(button("打开抖音商品", false, view -> {
                try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(productLink))); }
                catch (android.content.ActivityNotFoundException | SecurityException error) { toast("手机暂时无法打开商品链接"); }
            }), rowParams());
        }
        addCoreData(body, record);
        JSONArray covers = record.optJSONArray("product_images");
        if (record.has("product_image_status")) {
            int saved = covers == null ? 0 : covers.length();
            int expected = record.optInt("product_image_count");
            heading(body, "顶部商品轮播图");
            body.addView(text(expected > 0 ? "已采 " + saved + " / " + expected + " 张" : "未采到轮播图", 14, MUTED));
            for (int i = 0; i < saved; i++) {
                JSONObject image = covers.optJSONObject(i);
                if (image == null || !new File(image.optString("file", "")).isFile()) continue;
                body.addView(button("查看第 " + image.optInt("position", i + 1) + " 张轮播图", false,
                        view -> showImage(image.optString("file"))), rowParams());
            }
        } else {
            JSONObject mainImage = record.optJSONObject("main_image");
            if (mainImage != null && new File(mainImage.optString("file", "")).isFile()) {
                body.addView(button("查看商品主图", false, view -> showImage(mainImage.optString("file"))), rowParams());
            } else {
                body.addView(text(mainImage != null && record.optBoolean("uploaded", false) ? "商品主图已回传" : "本条记录未附主图", 14, MUTED));
            }
        }
        String video = firstNonempty(record, "video_url");
        if (!video.isEmpty()) {
            body.addView(button("打开商品视频链接", false, view -> openVideo(video)), rowParams());
        } else {
            boolean fromKaogu = record.has("kaogujia_key") || record.has("batch_id");
            body.addView(text(fromKaogu ? "考古加未提供商品视频链接" : "本条记录未提供商品视频链接", 14, MUTED));
        }
        JSONArray tags = record.optJSONArray("product_tags");
        if (tags != null && tags.length() > 0) {
            StringBuilder tagText = new StringBuilder();
            for (int index = 0; index < tags.length(); index++) {
                if (index > 0) tagText.append(" · ");
                tagText.append(tags.optString(index));
            }
            body.addView(text(tagText.toString(), 14, GREEN));
        }
        JSONArray skus = record.optJSONArray("skus");
        if (skus != null) for (int index = 0; index < skus.length(); index++) {
            JSONObject sku = skus.optJSONObject(index);
            if (sku == null) continue;
            TextView name = text(sku.optString("name", "规格 " + (index + 1)) + (sku.optBoolean("hot", false) ? " · 热销" : ""), 16, INK);
            name.setPadding(0, dp(14), 0, dp(4));
            name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            body.addView(name);
            body.addView(text(sku.optString("price_before_label", "页面价格") + " " + money(sku, "price_before_coupon_fen")
                    + "\n" + sku.optString("price_after_label", "优惠后价格") + " " + money(sku, "price_after_coupon_fen"), 15, GREEN));
            boolean imageNotRequested = "not_requested".equals(sku.optString("image_status", ""));
            if (imageNotRequested) body.addView(text("未采集图片（本次未启用）", 14, MUTED));
            JSONObject image = sku.optJSONObject("image");
            if (image != null && new File(image.optString("file", "")).isFile()) {
                body.addView(button("查看规格图片", false, view -> showImage(image.optString("file"))), rowParams());
            } else if (image != null && record.optBoolean("uploaded", false)) {
                body.addView(text("图片已回传", 14, MUTED));
            } else if (!imageNotRequested && sku.has("image_note")) {
                body.addView(text(sku.optString("image_note"), 14, MUTED));
            }
        }
        if (!record.optString("sku_note", "").isEmpty()) body.addView(text(record.optString("sku_note"), 14, MUTED));
        if (!record.optString("upload_error", "").isEmpty()) body.addView(text(record.optString("upload_error"), 14, MUTED));
        new AlertDialog.Builder(this).setTitle(record.optString("title", "采集记录"))
                .setView(scroll).setPositiveButton("关闭", null).show();
    }

    private void addProductNames(LinearLayout body, JSONObject record) {
        JSONObject detail = record.optJSONObject("source_detail");
        String kaoguName = firstNonempty(record, "kaogujia_product_name", "source_title");
        String douyinName = firstNonempty(record, "douyin_product_name", "title");
        String kaoguShop = firstNonempty(record, "kaogujia_shop_name");
        if (kaoguShop.isEmpty() && detail != null) kaoguShop = firstNonempty(detail, "shop_name");
        if (kaoguShop.isEmpty()) kaoguShop = firstNonempty(record, "shop_name");
        String douyinShop = firstNonempty(record, "douyin_shop_name");
        heading(body, "商品与店铺名称");
        body.addView(text("考古加商品名称：" + (kaoguName.isEmpty() ? "未采到" : kaoguName), 15, INK));
        body.addView(text("考古加店铺名称：" + (kaoguShop.isEmpty() ? "未采到" : kaoguShop), 15, MUTED));
        body.addView(text("抖音商品名称：" + (douyinName.isEmpty() ? "未采到" : douyinName), 15, INK));
        body.addView(text("抖音店铺名称：" + (douyinShop.isEmpty() ? "未采到" : douyinShop), 15, MUTED));
    }

    private void addCoreData(LinearLayout body, JSONObject record) {
        JSONObject source = record.optJSONObject("source_detail");
        if (source == null && !"kaogujia_app".equals(record.optString("source_mode"))) return;
        if (source == null) source = new JSONObject();
        JSONObject core = source.optJSONObject("core_data");
        if (core == null) core = new JSONObject();
        heading(body, "考古加核心数据");
        String period = firstNonempty(source, "core_data_period");
        String dates = firstNonempty(source, "core_data_date_text");
        body.addView(text("统计周期：" + (period.isEmpty() ? "未采到" : period), 14, MUTED));
        body.addView(text("日期范围：" + (dates.isEmpty() ? "未采到" : dates), 14, MUTED));
        boolean complete = source.optBoolean("core_data_complete", false);
        for (String name : new String[]{"商品销量", "直播销量", "视频销量", "商品卡销量"}) {
            String value = firstNonempty(core, name);
            if (value.isEmpty()) complete = false;
            body.addView(text(name + "：" + (value.isEmpty() ? "未采到" : value), 16, INK));
        }
        body.addView(text(complete ? "四项销量已完整读取" : "核心数据未完整采集", 13,
                complete ? GREEN : Color.rgb(156, 82, 18)));
    }

    private void openVideo(String address) {
        Uri uri = Uri.parse(address);
        if ((!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme())) || uri.getHost() == null) {
            toast("提供的视频链接暂时无法打开");
            return;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (android.content.ActivityNotFoundException | SecurityException error) {
            toast("手机暂时没有可打开该视频链接的应用");
        }
    }

    private void showImage(String file) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 2;
        Bitmap bitmap = BitmapFactory.decodeFile(file, options);
        if (bitmap == null) {
            toast("图片暂时无法读取");
            return;
        }
        ImageView image = new ImageView(this);
        image.setAdjustViewBounds(true);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setImageBitmap(bitmap);
        new AlertDialog.Builder(this).setView(image).setPositiveButton("关闭", null)
                .setOnDismissListener(dialog -> { image.setImageDrawable(null); bitmap.recycle(); }).show();
    }

    private void exportRecords() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.setType("application/json");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.putExtra(Intent.EXTRA_TITLE, "手机采集结果-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".json");
        startActivityForResult(intent, EXPORT_REQUEST);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != EXPORT_REQUEST || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri target = data.getData();
        workers.execute(() -> {
            String message;
            try (OutputStream stream = getContentResolver().openOutputStream(target, "wt")) {
                if (stream == null) throw new java.io.IOException();
                stream.write(RecordStore.records(this).toString(2).getBytes(StandardCharsets.UTF_8));
                message = "JSON 已导出";
            } catch (Exception exception) {
                message = "导出失败，手机中的记录已保留";
            }
            String outcome = message;
            runOnUiThread(() -> { if (!isDestroyed()) toast(outcome); });
        });
    }

    private String stateValue(String key, String fallback) {
        Object value = getSharedPreferences("collector_state", MODE_PRIVATE).getAll().get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    private static boolean isPaused(String status) {
        return status.equals("paused") || status.contains("暂停");
    }

    private static String statusName(String status) {
        if (status.equals("running") || status.equals("collecting")) return "采集中";
        if (isPaused(status)) return "已暂停";
        if (status.equals("completed") || status.equals("complete")) return "采集完成";
        if (status.equals("partial")) return "部分完成";
        if (status.equals("stopped")) return "任务已结束";
        if (status.equals("error") || status.equals("blocked")) return "需要处理";
        if (status.equals("waiting") || status.equals("needs_user")) return "等待处理";
        return status.equals("idle") || status.isEmpty() ? "待命" : status;
    }

    private SharedPreferences preferences() {
        PlatformRoute route = platformRegistry.get(activePlatformId);
        return getSharedPreferences(route == null ? "collector_settings" : route.settingsName, MODE_PRIVATE);
    }

    private static String money(JSONObject row, String key) {
        if (!row.has(key) || row.isNull(key)) return "未核对";
        Object value = row.opt(key);
        if (!(value instanceof Number)) return "未核对";
        return "¥" + BigDecimal.valueOf(((Number) value).longValue(), 2).toPlainString();
    }

    private static String byteSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024));
    }

    private LinearLayout vertical() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }

    private LinearLayout card(LinearLayout parent) {
        LinearLayout view = vertical();
        view.setPadding(dp(17), dp(17), dp(17), dp(17));
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(Color.WHITE);
        shape.setCornerRadius(dp(18));
        view.setBackground(shape);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(16);
        parent.addView(view, params);
        return view;
    }

    private void heading(LinearLayout parent, String value) {
        TextView title = text(value, 21, INK);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setPadding(0, 0, 0, dp(12));
        parent.addView(title);
    }

    private void label(LinearLayout parent, String value) {
        TextView label = text(value, 14, MUTED);
        label.setPadding(0, dp(10), 0, dp(5));
        parent.addView(label);
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setLineSpacing(dp(4), 1);
        view.setPadding(0, dp(3), 0, dp(3));
        return view;
    }

    private EditText input(String hint, int type) {
        EditText field = new EditText(this);
        field.setTextSize(16);
        field.setTextColor(INK);
        field.setHintTextColor(MUTED);
        field.setHint(hint);
        field.setInputType(type);
        field.setSingleLine(true);
        field.setPadding(0, dp(7), 0, dp(7));
        field.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(52)));
        return field;
    }

    private Button button(String value, boolean primary, View.OnClickListener action) {
        Button view = new Button(this);
        view.setText(value);
        view.setTextSize(15);
        view.setAllCaps(false);
        view.setGravity(Gravity.CENTER);
        view.setMinHeight(dp(48));
        view.setMinimumHeight(dp(48));
        view.setPadding(dp(10), dp(9), dp(10), dp(9));
        view.setOnClickListener(action);
        styleButton(view, primary);
        return view;
    }

    private void styleButton(Button view, boolean primary) {
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(10));
        background.setColor(primary ? GREEN : Color.rgb(232, 242, 236));
        view.setBackground(background);
        view.setTextColor(primary ? Color.WHITE : GREEN);
    }

    private LinearLayout.LayoutParams rowParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(49));
        params.topMargin = dp(10);
        return params;
    }

    private LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(0, dp(49), 1);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toast(String value) {
        Toast.makeText(this, value, Toast.LENGTH_SHORT).show();
    }
}
