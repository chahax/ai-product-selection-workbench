package local.ai.collector;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.Bundle;
import android.util.AtomicFile;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Executes one current-page collection task inside the selected platform app. */
public final class CollectorService extends AccessibilityService {
    public static final String MALL_PACKAGE = "com.ss.android.ugc.livelite";
    private static final String PLATFORM = "douyin_mall";
    private static volatile CollectorService instance;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable step = this::tickSafely;
    private final ExecutorService batchIo = Executors.newSingleThreadExecutor();
    private final List<LinkedHashMap<String, String>> combinations = new ArrayList<>();
    private final List<String> groupNames = new ArrayList<>();
    private JSONObject record;
    private JSONObject currentSku;
    private Bitmap thumbnail;
    private Bitmap preview;
    private Rect imageBounds;
    private long productLinkCopiedAfter;
    private String stage = "idle";
    private String pausedStage = "discover";
    private String stablePrice = "";
    private String acceptedPrice = "";
    private String blockedSelection = "";
    private String returnPrice = "";
    private long stageStarted;
    private long nextShot;
    private long generation;
    private long screenshotSequence;
    private long stableSince;
    private long selectionChangedAt;
    private long combinationStarted;
    private long returnStableSince;
    private int combinationIndex = -1;
    private int selectionClicks;
    private int priceRestarts;
    private int stableSamples;
    private int blockedSamples;
    private int index;
    private int attempts;
    private boolean couponEntryTried;
    private int pageScrolls;
    private volatile boolean running;
    private boolean paused;
    private boolean capturing;
    private boolean collectionTruncated;
    private boolean dataIncomplete;
    private boolean resuming;
    private boolean resumeTitleVerified;
    private boolean batchMode;
    private boolean appSourceMode;
    private boolean keepAlive;
    private String targetPackage = MALL_PACKAGE;
    private String appDateRange = "30d";
    private String appCategoryPath = "";
    private boolean appCategorySearchSubmitted;
    private int appLimit = 5;
    private String appNavigationStage = "app_list";
    private String appPreparation = "date";
    private long appStageStarted;
    private String appListSignature = "";
    private int appStableFrames;
    private int appScrolls;
    private int appNoNew;
    private boolean appRewind;
    private String appCandidate = "";
    private int appEntryRetries;
    private String appRewindSignature = "";
    private int appRewindStable;
    private int appPreviewBackAttempts;
    private JSONObject appSourceDetail = new JSONObject();
    private boolean stoppingBatch;
    private String navigationStage = "mall_home";
    private long navigationStageStarted;
    private long navigationStarted;
    private int qrRetries;
    private boolean refreshedProduct;
    private String navigationError = "";
    private long navigationErrorStarted;

    public static boolean startKaoguAppBatch(Context context, String dateRange, int limit, String category) {
        CollectorService service = instance;
        if (service == null || service.running && !service.paused
                || WorkbenchClient.get(context).hasJob()
                || KaoguAppNavigator.normalizeDateRange(dateRange).isEmpty() || limit < 1 || limit > 200) return false;
        service.handler.post(() -> {
            if (service.running && !service.paused || WorkbenchClient.get(service).hasJob()) return;
            try { service.beginKaoguApp(dateRange, limit, category, ""); }
            catch (Exception error) { service.pause("考古加App任务尚未开始：" + readableError(error)); }
        });
        return true;
    }

    static boolean canAcceptWorkbenchJob(Context context) {
        CollectorService service = instance;
        if (service == null || service.running) return false;
        JSONObject batch = BatchStore.summary(context);
        String status = batch.optString("status");
        return ("idle".equals(status) || "complete".equals(status) || "stopped".equals(status))
                && BatchStore.qrReceipt(context) == null;
    }

    static void startWorkbenchJob(Context context, String jobId, String dateRange, int limit, String category) {
        CollectorService service = instance;
        if (service == null) return;
        service.handler.post(() -> {
            WorkbenchClient client = WorkbenchClient.get(context);
            if (service != instance || !client.canStart(jobId)) return;
            try {
                if (!canAcceptWorkbenchJob(service)) return;
                service.beginKaoguApp(dateRange, limit, category, jobId);
            } catch (Exception error) {
                try {
                    if (jobId.equals(BatchStore.summary(service).optString("workbench_job_id"))) {
                        service.pause("考古加 App 任务尚未启动：" + readableError(error));
                        return;
                    }
                } catch (RuntimeException ignored) { }
                client.startFailed(jobId, "无法在手机保存批次：" + readableError(error));
            }
        });
    }

    public static boolean startBatch(Context context) {
        CollectorService service = instance;
        if (service == null || service.running && !service.paused || service.running && !service.batchMode) return false;
        try {
            JSONObject summary = BatchStore.summary(context);
            if (BatchStore.current(context) == null && !summary.optBoolean("preparing") || "stopped".equals(summary.optString("status"))) return false;
        } catch (Exception error) { return false; }
        service.handler.post(() -> {
            if (service.running && !service.paused) return;
            try { service.beginBatch(); }
            catch (Exception error) { service.pause("批次尚未开始：" + readableError(error)); }
        });
        return true;
    }

    public static void qrReady(Context context) {
        CollectorService service = instance;
        if (service == null) return;
        service.handler.post(() -> {
            try {
                if (!service.batchMode || !service.running || service.paused || !"batch_fetch".equals(service.stage)) return;
                JSONObject receipt = BatchStore.qrReceipt(service);
                JSONObject item = BatchStore.current(service);
                if (receipt == null || item == null || !receipt.optString("product_id").equals(item.optString("product_id"))
                        || !receipt.optString("qr_request_id").equals(item.optString("qr_request_id"))) return;
                service.navigationStage = "mall_home";
                service.refreshedProduct = false;
                service.navigationError = "";
                service.navigationStarted = service.navigationStageStarted = SystemClock.uptimeMillis();
                service.setStage("batch_scan", "正在进入抖音商城扫码");
                BatchStore.phase(service, "二维码已准备，正在打开商城扫码", "scanning");
                service.launchMall();
            } catch (Exception error) { service.pause("扫码准备未完成：" + readableError(error)); }
        });
    }

    public static void batchBrowserFailure(Context context, String message) {
        CollectorService service = instance;
        if (service != null) service.handler.post(() -> {
            if (service.batchMode && service.running && !service.paused && "batch_fetch".equals(service.stage)
                    && "paused".equals(BatchStore.summary(service).optString("status"))) service.pause(message);
        });
    }

    public static boolean isConnected() {
        return instance != null;
    }

    public static boolean startTask(Context context) {
        CollectorService service = instance;
        if (service == null) {
            context.getSharedPreferences("collector_state", MODE_PRIVATE).edit()
                    .putString("status", "idle").putString("phase", "请先开启手机商品采集的无障碍服务").apply();
            return false;
        }
        if (service.running || WorkbenchClient.get(context).hasJob()) {
            return false;
        }
        service.handler.post(() -> {
            if (!service.running && !WorkbenchClient.get(service).hasJob()) service.begin();
        });
        return true;
    }

    public static void command(String command) {
        CollectorService service = instance;
        if (service != null) {
            service.handler.post(() -> service.handleCommand(command));
        }
    }

    public static void skipPausedBatchItem(String reason) {
        CollectorService service = instance;
        if (service == null) return;
        service.handler.post(() -> {
            if (instance != service || !service.batchMode || !service.running || !service.paused) return;
            try {
                JSONObject item = BatchStore.current(service);
                if (item == null || BatchStore.summary(service).optBoolean("stop_requested")) return;
                if (!sameBatchItem(service.record, item)) service.record = null;
                BatchStore.activate(service);
                service.paused = false;
                service.skipBatchItem(reason);
            } catch (Exception error) {
                service.pause("暂时无法跳过当前商品：" + readableError(error));
            }
        });
    }

    @Override
    protected void onServiceConnected() {
        instance = this;
        getSharedPreferences("collector_state", MODE_PRIVATE).edit().putBoolean("accessibility_connected", true).apply();
        loadCheckpoint();
        appSourceMode = "kaogujia_app".equals(BatchStore.summary(this).optString("source_mode"));
        if (running && paused && batchMode) {
            try { BatchStore.pause(this, "已恢复批次，点击继续可从未完成商品接着采集"); }
            catch (Exception error) { publish("paused", "批次进度需要核对，请保留应用数据"); }
        }
        if (!running) {
            try {
                String batchStatus = BatchStore.summary(this).optString("status");
                if ("running".equals(batchStatus) || "paused".equals(batchStatus)
                        || "stopped".equals(batchStatus) && BatchStore.qrReceipt(this) != null) {
                    batchMode = true;
                    running = true;
                    paused = true;
                    BatchStore.pause(this, "已恢复批次，点击继续可从未完成商品接着采集");
                    publish("paused", "已恢复考古加批次，等待继续");
                }
            } catch (Exception error) { publish("paused", "批次进度需要核对，请保留应用数据"); }
        }
        WorkbenchClient.get(this);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (running && !paused && event.getPackageName() != null
                && (targetPackage.contentEquals(event.getPackageName())
                    || appSourceMode && (KaoguAppAdapter.PACKAGE.contentEquals(event.getPackageName())
                        || "com.ss.android.ugc.aweme".contentEquals(event.getPackageName())))) {
            if (!handler.hasCallbacks(step)) {
                schedule(350);
            }
        }
    }

    @Override
    public void onInterrupt() {
        if (running) {
            pause("系统中断了无障碍服务，任务已保留");
        }
    }

    @Override
    public void onDestroy() {
        invalidateCaptures();
        if (running) {
            paused = true;
            if (batchMode) {
                try { BatchStore.pause(this, "采集服务已停止，批次进度已保留"); } catch (Exception ignored) { }
            }
            publish("paused", "服务已停止，采集进度已保留");
            checkpoint();
        }
        handler.removeCallbacksAndMessages(null);
        batchIo.shutdown();
        recycleImages();
        instance = null;
        getSharedPreferences("collector_state", MODE_PRIVATE).edit().putBoolean("accessibility_connected", false).apply();
        super.onDestroy();
    }

    private void begin() {
        if (running) {
            return;
        }
        batchMode = false;
        appSourceMode = false;
        targetPackage = MALL_PACKAGE;
        invalidateCaptures();
        combinations.clear();
        groupNames.clear();
        recycleImages();
        index = 0;
        attempts = 0;
        pageScrolls = 0;
        collectionTruncated = false;
        dataIncomplete = false;
        resuming = false;
        resumeTitleVerified = false;
        currentSku = null;
        combinationIndex = -1;
        record = new JSONObject();
        put(record, "record_id", UUID.randomUUID().toString());
        put(record, "task_id", UUID.randomUUID().toString());
        put(record, "platform", PLATFORM);
        put(record, "platform_name", "抖音商城");
        put(record, "source_application", targetPackage);
        put(record, "captured_at", Instant.now().toString());
        put(record, "identity_basis", "用户指定的当前商品页");
        put(record, "skus", new JSONArray());
        running = true;
        paused = false;
        setStage("discover", "正在打开抖音商城，请停留在要采集的商品页");
        launchMall();
    }

    private void handleCommand(String command) {
        if ("pause_batch".equals(command) || batchMode && "pause".equals(command)) {
            if (running) pause("批次已暂停，当前商品进度已保留");
            return;
        }
        if ("stop_batch".equals(command) || batchMode && "stop".equals(command)) {
            if (batchMode) stopBatch();
            else {
                try { BatchStore.stop(this, "批次已结束"); } catch (Exception ignored) { }
            }
            return;
        }
        if (batchMode && "resume".equals(command)) {
            try { beginBatch(); } catch (Exception error) { pause(readableError(error)); }
            return;
        }
        if ("stop".equals(command)) {
            if (record != null && running) {
                finish("stopped", "任务已结束，已采集内容已保存");
            }
            return;
        }
        if ("pause".equals(command) && running && !paused) {
            pause("任务已暂停，当前进度已保留");
            return;
        }
        if ("resume".equals(command)) {
            if (record == null) {
                loadCheckpoint();
            }
            if (record == null || !running) {
                publish("idle", "没有待继续的采集任务");
                return;
            }
            running = true;
            paused = false;
            invalidateCaptures();
            recycleImages();
            currentSku = null;
            combinationIndex = -1;
            pageScrolls = 0;
            resuming = !combinations.isEmpty();
            resumeTitleVerified = false;
            setStage("discover", "正在核对原商品身份，请回到原商品详情页");
            launchMall();
        }
    }

    private void launchMall() {
        Intent intent = getPackageManager().getLaunchIntentForPackage(targetPackage);
        if (intent == null) {
            pause("手机尚未安装抖音商城");
            return;
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        startActivity(intent);
        schedule(1200);
    }

    private void beginBatch() {
        if ("kaogujia_app".equals(BatchStore.summary(this).optString("source_mode"))) {
            resumeKaoguApp();
            return;
        }
        if (BatchStore.summary(this).optBoolean("stop_requested")) {
            stopBatch();
            return;
        }
        if (running && !batchMode) throw new IllegalStateException("请先结束当前商品任务");
        JSONObject item = BatchStore.current(this);
        if (item == null) throw new IllegalStateException("请先从考古加读取商品名单");
        String batchId = item.optString("batch_id");
        boolean keepRecord = record != null && batchId.equals(record.optString("batch_id"))
                && item.optString("product_id").equals(record.optString("product_id"));
        if (!keepRecord) {
            record = null;
            combinations.clear();
            index = 0;
        }
        invalidateCaptures();
        recycleImages();
        currentSku = null;
        batchMode = true;
        stoppingBatch = false;
        running = true;
        paused = false;
        qrRetries = 0;
        BatchStore.activate(this);
        // Reconcile a process death after record commit but before queue advancement.
        JSONArray records = RecordStore.records(this);
        for (int i = 0; i < records.length(); i++) {
            JSONObject saved = records.optJSONObject(i);
            String status = saved.optString("status");
            if (batchId.equals(saved.optString("batch_id")) && item.optString("product_id").equals(saved.optString("product_id"))
                    && ("complete".equals(status) || "partial".equals(status))) {
                BatchStore.finishItem(this, status, saved.optString("record_id"), "");
                record = null;
                combinations.clear();
                break;
            }
        }
        cleanBatchQr(this::continueBatch);
    }

    private static String batchRecordId(JSONObject item) {
        String identity = item.optString("source_item_key");
        if (identity.isEmpty()) identity = item.optString("product_id");
        if (identity.isEmpty()) throw new IllegalStateException("本件商品缺少来源标识");
        return "batch-" + item.optString("batch_id") + "-" + identity;
    }

    private static boolean sameBatchItem(JSONObject saved, JSONObject item) {
        if (saved == null || item == null || !item.optString("batch_id").equals(saved.optString("batch_id"))) return false;
        String key = item.optString("source_item_key");
        if (!key.isEmpty()) return key.equals(saved.optString("source_item_key"));
        String id = item.optString("product_id");
        return !id.isEmpty() && id.equals(saved.optString("product_id"));
    }

    private void beginKaoguApp(String dateRange, int limit, String category, String workbenchJobId) {
        boolean fromWorkbench = !workbenchJobId.isEmpty();
        if (fromWorkbench) BatchStore.beginApp(this, dateRange, limit, category, workbenchJobId);
        if (!fromWorkbench && record != null && record.optJSONArray("skus") != null && record.optJSONArray("skus").length() > 0) {
            put(record, "status", "stopped");
            RecordStore.save(this, record);
        }
        invalidateCaptures();
        recycleImages();
        record = null;
        currentSku = null;
        combinations.clear();
        index = 0;
        batchMode = true;
        appSourceMode = false;
        stoppingBatch = false;
        running = true;
        paused = false;
        if (!fromWorkbench) BatchStore.stop(this, "已切换为考古加App来源，旧进度即将归档");
        new AtomicFile(new File(getFilesDir(), "active_task.json")).delete();
        cleanBatchQr(() -> {
            if (!fromWorkbench) BatchStore.beginApp(this, dateRange, limit, category, "");
            appDateRange = dateRange;
            appCategoryPath = category;
            appLimit = limit;
            appSourceMode = true;
            initializeAppPreparation();
            launchKaoguApp();
        });
    }

    private void resumeKaoguApp() {
        if (BatchStore.summary(this).optBoolean("stop_requested")) { stopBatch(); return; }
        JSONObject configuration = BatchStore.settings(this);
        appDateRange = configuration.optString("date_range", "30d");
        appCategoryPath = configuration.optString("category_path");
        appLimit = configuration.optInt("limit", 5);
        batchMode = appSourceMode = true;
        running = true;
        paused = false;
        stoppingBatch = false;
        invalidateCaptures();
        recycleImages();
        currentSku = null;
        BatchStore.activate(this);
        if (BatchStore.summary(this).optBoolean("preparing")) {
            BatchStore.resetAppList(this);
            record = null;
            combinations.clear();
            initializeAppPreparation();
        } else {
            JSONObject item = BatchStore.current(this);
            if (!sameBatchItem(record, item)) { record = null; combinations.clear(); index = 0; }
            JSONArray saved = RecordStore.records(this);
            for (int i = 0; i < saved.length(); i++) {
                JSONObject candidate = saved.optJSONObject(i);
                String status = candidate.optString("status");
                if (sameBatchItem(candidate, item) && ("complete".equals(status) || "partial".equals(status))) {
                    BatchStore.finishItem(this, status, candidate.optString("record_id"), "");
                    record = null;
                    combinations.clear();
                    break;
                }
            }
            appRewind = true;
            continueAppQueue();
        }
        launchKaoguApp();
    }

    private void initializeAppPreparation() {
        appPreparation = appCategoryPath.isEmpty() ? "date" : "category";
        appCategorySearchSubmitted = false;
        appNavigationStage = "app_list";
        appStageStarted = SystemClock.uptimeMillis();
        appStableFrames = appScrolls = appNoNew = 0;
        appListSignature = "";
        appSourceDetail = new JSONObject();
        setStage("batch_app_prepare", "正在按脚本参数核对考古加日期和商品名单");
        BatchStore.phase(this, "正在切换为“" + KaoguAppNavigator.normalizeDateRange(appDateRange) + "”并读取前 " + appLimit + " 件商品", null);
    }

    private void launchKaoguApp() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null) {
            boolean alreadyOpen = KaoguAppAdapter.PACKAGE.contentEquals(String.valueOf(root.getPackageName()));
            root.recycle();
            if (alreadyOpen) { schedule(700); return; }
        }
        Intent intent = getPackageManager().getLaunchIntentForPackage(KaoguAppAdapter.PACKAGE);
        if (intent == null) { pause("手机尚未安装考古加App"); return; }
        intent.setClassName(KaoguAppAdapter.PACKAGE, "com.fz.kaogujia.ui.activity.HomeNewActivty");
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
        schedule(1200);
    }

    private void continueAppQueue() {
        JSONObject item = BatchStore.current(this);
        if (item == null) { completeBatch(); return; }
        String status = item.optString("status");
        while ("complete".equals(status) || "partial".equals(status) || "failed".equals(status)) {
            if (!BatchStore.advance(this)) { completeBatch(); return; }
            record = null;
            combinations.clear();
            index = 0;
            item = BatchStore.current(this);
            if (item == null) { completeBatch(); return; }
            status = item.optString("status");
        }
        appNavigationStage = "find_item";
        appStageStarted = SystemClock.uptimeMillis();
        appScrolls = 0;
        appCandidate = "";
        appEntryRetries = 0;
        appRewindSignature = "";
        appRewindStable = 0;
        appSourceDetail = new JSONObject();
        setStage("batch_app_source", "正在从考古加列表打开本件商品");
        launchKaoguApp();
    }

    private void tickKaoguApp() throws Exception {
        android.util.Log.i("PhoneCollector", "app_tick " + stage + " " + appNavigationStage);
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getPackageName() == null) {
            if (elapsed() > 25000) pause("请保持手机解锁，并允许采集程序切换应用");
            else schedule(700);
            if (root != null) root.recycle();
            return;
        }
        String observedPackage = root.getPackageName().toString();
        android.util.Log.i("PhoneCollector", "app_root " + observedPackage);
        try {
            if (observedPackage.equals(getPackageName())) {
                if (elapsed() > 20000) pause("等待考古加App打开，请核对系统的应用跳转提示");
                else schedule(700);
                return;
            }
            KaoguAppAdapter.Snapshot source = KaoguAppAdapter.PACKAGE.equals(observedPackage)
                    && !"verify_destination".equals(appNavigationStage) ? KaoguAppAdapter.read(root) : null;
            if (source != null) {
                if (source.productPreview) {
                    boolean openingItem = "app_detail".equals(appNavigationStage) && source.previewDetails != null;
                    if (++appPreviewBackAttempts > 3 || !openingItem && !performGlobalAction(GLOBAL_ACTION_BACK)) {
                        pause("考古加商品预览未能退出，请关闭预览后继续当前批次");
                        return;
                    }
                    if (openingItem) clickNavigation(source.previewDetails, observedPackage);
                    String message = openingItem ? "正在从考古加预览打开数据详情" : "正在关闭考古加商品预览，返回原筛选列表";
                    BatchStore.phase(this, message, null);
                    publish("running", message);
                    appStageStarted = SystemClock.uptimeMillis();
                    schedule(900);
                    return;
                }
                appPreviewBackAttempts = 0;
            }
            if ("batch_app_prepare".equals(stage)) {
                prepareAppList(root, observedPackage, source);
                return;
            }
            JSONObject item = BatchStore.current(this);
            if (item == null) { completeBatch(); return; }
            if ("verify_destination".equals(appNavigationStage)
                    && !KaoguAppAdapter.PACKAGE.equals(observedPackage)
                    && !MALL_PACKAGE.equals(observedPackage) && !"com.ss.android.ugc.aweme".equals(observedPackage)) {
                // HyperOS briefly places its cross-app check window on top even
                // when the jump is already allowed. Wait for the actual target.
                if (SystemClock.uptimeMillis() - appStageStarted < 26000) { schedule(700); return; }
                pause("应用跳转提示尚未结束，请核对手机后继续");
                return;
            }
            if (SystemClock.uptimeMillis() - appStageStarted > 150000) {
                finishBatchItem("failed", "未能在当前筛选列表定位并打开本件商品");
                return;
            }
            if (appRewind && source != null) {
                if (openProductLibrary(source)) return;
                if (source.list && !appCategoryPath.isEmpty() && !appCategoryName().equals(source.category)) {
                    pause("商品库类目已变化，请选回「" + appCategoryName() + "」后继续本批次");
                    return;
                }
                if (source.detail) {
                    performGlobalAction(GLOBAL_ACTION_BACK);
                    schedule(850);
                    return;
                }
                if (source.list && !source.dateMenu) {
                    boolean found = false;
                    StringBuilder visible = new StringBuilder();
                    for (KaoguAppAdapter.Card card : source.cards) {
                        if (card.title.equals(item.optString("title"))) found = true;
                        visible.append(card.title).append('\n');
                    }
                    String signature = visible.toString();
                    appRewindStable = signature.equals(appRewindSignature) ? appRewindStable + 1 : 0;
                    appRewindSignature = signature;
                    if (!found && appRewindStable < 2 && appScrolls++ < 80) {
                        scrollSource(root, false);
                        schedule(1000);
                        return;
                    }
                    appRewind = false;
                }
            }
            if ("app_detail".equals(appNavigationStage) && source != null
                    && SystemClock.uptimeMillis() - appStageStarted > 1800
                    && source.list && appEntryRetries < 2) {
                appEntryRetries++;
                appCandidate = "";
                appNavigationStage = "find_item";
                appStageStarted = SystemClock.uptimeMillis();
                schedule(750);
                return;
            }
            if ("verify_destination".equals(appNavigationStage)
                    && (MALL_PACKAGE.equals(observedPackage) || "com.ss.android.ugc.aweme".equals(observedPackage))) {
                DouyinAdapter.Snapshot target = DouyinAdapter.read(root);
                android.util.Log.i("PhoneCollector", "target title=" + target.title + " popup=" + target.popup + " gallery=" + target.gallery);
                if (target.intervention.isEmpty() && (target.popup || target.gallery)) {
                    if (appStableFrames++ >= 2) { pause("返回后仍未显示商品详情，请核对目标页面"); return; }
                    performGlobalAction(GLOBAL_ACTION_BACK);
                    schedule(1400);
                    return;
                }
            }
            KaoguAppNavigator.Step decision = KaoguAppNavigator.next(root, source, appNavigationStage, item, appDateRange);
            android.util.Log.i("PhoneCollector", "app_decision " + decision.nextStage + " ready=" + decision.ready + " message=" + decision.message + " intervention=" + decision.intervention);
            if (SystemClock.uptimeMillis() - appStageStarted > decision.timeoutMs) {
                finishBatchItem("failed", decision.timeoutMessage);
                return;
            }
            if (!decision.intervention.isEmpty()) {
                if ((decision.intervention.contains("标题与名单不一致") || decision.intervention.contains("完整标题与本件不一致"))
                        && SystemClock.uptimeMillis() - appStageStarted < 6000) { schedule(800); return; }
                if (DouyinAdapter.isUnavailable(decision.intervention)) {
                    skipBatchItem(decision.intervention);
                } else if (decision.intervention.contains("网络异常") || decision.intervention.contains("商品已下架") || decision.intervention.contains("商品不存在") || decision.intervention.contains("商品已失效")
                        || decision.intervention.contains("标题与名单不一致") || decision.intervention.contains("完整标题与本件不一致")) {
                    finishBatchItem("failed", decision.intervention);
                } else pause(decision.intervention);
                return;
            }
            BatchStore.phase(this, decision.message, "opening_product");
            publish("running", decision.message);
            JSONObject detail = decision.metadata.optJSONObject("source_detail");
            if (detail != null) {
                appSourceDetail = detail;
                JSONObject update = new JSONObject();
                put(update, "source_detail", detail);
                put(update, "shop_name", detail.optString("shop_name"));
                put(update, "source_title", item.optString("title"));
                BatchStore.updateCurrent(this, update);
            }
            if (decision.ready) {
                targetPackage = decision.targetPackage;
                JSONObject update = new JSONObject();
                put(update, "source_detail", appSourceDetail);
                put(update, "shop_name", appSourceDetail.optString("shop_name"));
                put(update, "source_title", item.optString("title"));
                put(update, "identity_basis", decision.metadata.optString("identity_basis"));
                BatchStore.updateCurrent(this, update);
                MallNavigator.Step verified = new MallNavigator.Step();
                verified.identityBasis = "kaogu_app_view_price";
                verified.identityTitle = decision.title;
                activateBatchProduct(BatchStore.current(this), verified);
                return;
            }
            if ("find_item".equals(appNavigationStage) && decision.click != null) {
                String candidate = decision.title + "|" + decision.click.toShortString();
                if (!candidate.equals(appCandidate)) {
                    appCandidate = candidate;
                    schedule(850);
                    return;
                }
            }
            if (decision.back) performGlobalAction(GLOBAL_ACTION_BACK);
            else if (decision.click != null) clickNavigation(decision.click, observedPackage);
            else if (decision.swipe != null) {
                appCandidate = "";
                if (++appScrolls > 100) { pause("未能在原筛选列表中找到本件，请核对筛选条件后继续"); return; }
                scrollSource(root, true);
            }
            if (!appNavigationStage.equals(decision.nextStage)) {
                appNavigationStage = decision.nextStage;
                appStageStarted = SystemClock.uptimeMillis();
                appStableFrames = 0;
            }
            schedule(decision.delayMs);
        } finally { root.recycle(); }
    }

    private boolean openProductLibrary(KaoguAppAdapter.Snapshot source) {
        if (source.list || source.detail || source.dateMenu || source.categoryFinish != null || source.categorySearchField != null
                || !source.intervention.isEmpty() || elapsed() > 20000) return false;
        Rect tab = source.productLibraryTab != null ? source.productLibraryTab : source.productTab;
        if (tab == null) return false;
        String message = "正在打开考古加商品库";
        BatchStore.phase(this, message, null);
        publish("running", message);
        clickNavigation(tab, KaoguAppAdapter.PACKAGE);
        schedule(1100);
        return true;
    }

    private void prepareAppCategory(AccessibilityNodeInfo root, KaoguAppAdapter.Snapshot source) {
        if (!source.intervention.isEmpty()) { pause(source.intervention); return; }
        if (source.list && appCategoryName().equals(source.category)) {
            appPreparation = "date";
            appStageStarted = SystemClock.uptimeMillis();
            schedule(850);
            return;
        }
        if (SystemClock.uptimeMillis() - appStageStarted > 25000) {
            pause("未确认考古加类目，请在商品库选择「" + appCategoryName() + "」后继续");
            return;
        }
        String message = "正在选择考古加类目「" + appCategoryName() + "」";
        BatchStore.phase(this, message, null);
        publish("running", message);
        if (source.detail || source.dateMenu) {
            performGlobalAction(GLOBAL_ACTION_BACK);
        } else if (source.categoryFinish == null && source.categorySearchField == null) {
            if (source.list && source.categoryControl != null) clickNavigation(source.categoryControl, KaoguAppAdapter.PACKAGE);
        } else if (source.categoryFinish != null && appCategoryPath.equals(source.categoryPath)) {
            clickNavigation(source.categoryFinish, KaoguAppAdapter.PACKAGE);
        } else if (KaoguAppAdapter.PERSONAL_CARE_PATH.equals(appCategoryPath) && source.personalCareOption != null
                && source.categoryPath.startsWith("3C数码家电-电器-")) {
            clickNavigation(source.personalCareOption, KaoguAppAdapter.PACKAGE);
        } else if (source.categorySearchResults.containsKey(appCategoryPath)) {
            clickNavigation(source.categorySearchResults.get(appCategoryPath), KaoguAppAdapter.PACKAGE);
        } else if (!appCategorySearchSubmitted) {
            searchAppCategory(root, source.categorySearchField, source.categorySearch);
        }
        schedule(750);
    }

    private String appCategoryName() {
        String prefix = KaoguAppAdapter.PERSONAL_CARE_PATH + "-";
        return appCategoryPath.startsWith(prefix) ? appCategoryPath.substring(prefix.length()) : KaoguAppAdapter.PERSONAL_CARE;
    }

    private void searchAppCategory(AccessibilityNodeInfo root, Rect searchField, Rect searchButton) {
        if (searchField == null) return;
        for (AccessibilityNodeInfo field : root.findAccessibilityNodeInfosByViewId(KaoguAppAdapter.PACKAGE + ":id/et_search")) {
            try {
                if (!field.isVisibleToUser() || !field.isEditable()) continue;
                Rect bounds = new Rect();
                field.getBoundsInScreen(bounds);
                if (!bounds.equals(searchField)) continue;
                if (searchButton == null || !bounds.intersects(bounds.left, searchButton.top, bounds.right, searchButton.bottom)) {
                    clickNavigation(bounds, KaoguAppAdapter.PACKAGE);
                } else if (appCategoryName().contentEquals(field.getText() == null ? "" : field.getText())) {
                    clickNavigation(searchButton, KaoguAppAdapter.PACKAGE);
                    appCategorySearchSubmitted = true;
                } else {
                    Bundle input = new Bundle();
                    input.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, appCategoryName());
                    field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, input);
                }
            } finally {
                field.recycle();
            }
        }
    }

    private void prepareAppList(AccessibilityNodeInfo root, String observedPackage, KaoguAppAdapter.Snapshot source) throws Exception {
        if (source == null) {
            if (elapsed() > 20000) pause("请停留在考古加App的已筛选商品列表");
            else schedule(700);
            return;
        }
        if (openProductLibrary(source)) return;
        if ("category".equals(appPreparation)) {
            prepareAppCategory(root, source);
            return;
        }
        if ("date".equals(appPreparation)) {
            KaoguAppNavigator.Step decision = KaoguAppNavigator.next(root, source, appNavigationStage, null, appDateRange);
            if (!decision.intervention.isEmpty()) { pause(decision.intervention); return; }
            BatchStore.phase(this, decision.message, null);
            publish("running", decision.message);
            if (decision.back) performGlobalAction(GLOBAL_ACTION_BACK);
            else if (decision.click != null) clickNavigation(decision.click, observedPackage);
            if (!appNavigationStage.equals(decision.nextStage)) {
                appNavigationStage = decision.nextStage;
                appStageStarted = SystemClock.uptimeMillis();
            }
            if ("find_item".equals(appNavigationStage)) {
                appPreparation = "top";
                appStableFrames = appScrolls = 0;
                appListSignature = "";
            } else if (SystemClock.uptimeMillis() - appStageStarted > 25000) {
                pause("日期选择尚未确认，请核对考古加页面后继续");
                return;
            }
            schedule(decision.delayMs);
            return;
        }
        if (!source.intervention.isEmpty()) { pause(source.intervention); return; }
        if (!source.list || source.dateMenu || !KaoguAppNavigator.normalizeDateRange(appDateRange).equals(source.selectedDate)
                || (!appCategoryPath.isEmpty() && !appCategoryName().equals(source.category))) {
            pause("读取名单时类目、日期或页面发生变化，请核对后继续");
            return;
        }
        StringBuilder signature = new StringBuilder();
        for (KaoguAppAdapter.Card card : source.cards) signature.append(card.title).append('\n');
        String observed = signature.toString();
        if ("top".equals(appPreparation)) {
            appStableFrames = observed.equals(appListSignature) ? appStableFrames + 1 : 0;
            appListSignature = observed;
            if (appStableFrames >= 2 || appScrolls++ > 80) {
                appPreparation = "collect";
                appNoNew = 0;
            } else {
                scrollSource(root, false);
                schedule(900);
                return;
            }
        }
        JSONArray cards = new JSONArray();
        for (KaoguAppAdapter.Card card : source.cards) {
            JSONObject row = new JSONObject();
            put(row, "title", card.title);
            put(row, "source_item_key", nativeSourceKey(card.title));
            put(row, "source_card", card.metadata);
            put(row, "shop_name", card.metadata.optString("shop_name"));
            cards.put(row);
        }
        int added = BatchStore.appendAppCards(this, cards);
        appNoNew = added == 0 ? appNoNew + 1 : 0;
        int count = BatchStore.items(this).length();
        BatchStore.phase(this, "已读取 " + count + " / " + appLimit + " 件商品名单", null);
        if (count >= appLimit || appNoNew >= 3 || appScrolls++ > 120) {
            if (count == 0) { pause("没有读到可核对的完整商品卡片"); return; }
            if (count < appLimit) { pause("仅确认到 " + count + " / " + appLimit + " 件，名单不足，请核对列表加载情况后继续"); return; }
            BatchStore.finishAppList(this, count < appLimit ? "当前筛选列表只确认到 " + count + " 件商品" : "");
            appRewind = true;
            continueAppQueue();
            return;
        }
        scrollSource(root, true);
        schedule(1100);
    }

    private void scrollSource(AccessibilityNodeInfo root, boolean forward) {
        Rect bounds = new Rect(); root.getBoundsInScreen(bounds);
        // Keep at least one whole row overlapping so a clipped row cannot be skipped.
        int x = bounds.centerX(), top = bounds.top + bounds.height() * 46 / 100, bottom = bounds.top + bounds.height() * 76 / 100;
        swipe(x, forward ? bottom : top, x, forward ? top : bottom);
    }

    private AccessibilityNodeInfo sourceScrollable(AccessibilityNodeInfo node) {
        String id = node.getViewIdResourceName();
        if (id != null && id.endsWith("/rec_daren") && node.isScrollable()) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            AccessibilityNodeInfo match = sourceScrollable(child);
            if (match != null) return match;
        }
        return null;
    }

    private static String nativeSourceKey(String title) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(title.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder("app-");
        for (int i = 0; i < 12; i++) result.append(String.format(java.util.Locale.ROOT, "%02x", digest[i] & 255));
        return result.toString();
    }

    private void continueBatch() {
        if (appSourceMode) { continueAppQueue(); return; }
        JSONObject item = BatchStore.current(this);
        if (item == null) {
            completeBatch();
            return;
        }
        String status = item.optString("status");
        if ("complete".equals(status) || "partial".equals(status) || "failed".equals(status)) {
            if (!BatchStore.advance(this)) {
                completeBatch();
                return;
            }
            record = null;
            combinations.clear();
            index = 0;
            qrRetries = 0;
        }
        JSONObject requested = BatchStore.requestQr(this);
        setStage("batch_fetch", "正在获取本件考古加商品二维码");
        KaoguActivity.requestQr(this, requested);
    }

    private void cleanBatchQr(Runnable next) {
        setStage("batch_cleanup", "正在清理本次采集的临时二维码");
        final long expectedGeneration = generation;
        batchIo.execute(() -> {
            Exception failure = null;
            try {
                JSONObject receipt = BatchStore.qrReceipt(this);
                if (receipt != null) {
                    QrAlbum.remove(this, receipt);
                    BatchStore.clearQr(this, receipt);
                }
                JSONArray pending = QrAlbum.pending(this);
                for (int i = 0; i < pending.length(); i++) QrAlbum.remove(this, pending.getJSONObject(i));
            } catch (Exception error) { failure = error; }
            final Exception result = failure;
            handler.post(() -> {
                if (instance != this || expectedGeneration != generation || !running || paused || !batchMode) return;
                if (result != null) {
                    pause("临时二维码尚未确认清理，进度已保留：" + readableError(result));
                    return;
                }
                try { next.run(); }
                catch (Exception error) { pause("批次暂时停止：" + readableError(error)); }
            });
        });
    }

    private void tickBatch() throws Exception {
        if (appSourceMode && !"batch_cleanup".equals(stage)) { tickKaoguApp(); return; }
        if ("batch_cleanup".equals(stage)) {
            if (elapsed() > 25000) pause("二维码清理尚未完成，请保留批次后继续");
            else schedule(800);
            return;
        }
        if ("batch_fetch".equals(stage)) {
            String status = BatchStore.summary(this).optString("status");
            if (!"running".equals(status)) {
                pause(BatchStore.summary(this).optString("phase", "考古加需要核对登录状态"));
            } else if (elapsed() > 70000) {
                pause("考古加取码尚未完成，请在应用内核对登录后继续");
            } else schedule(700);
            return;
        }
        if (!"batch_scan".equals(stage)) {
            pause("批次导航状态需要重新确认");
            return;
        }
        JSONObject item = BatchStore.current(this);
        if (item == null) { pause("当前商品名单未能读取"); return; }
        if (SystemClock.uptimeMillis() - navigationStarted > 105000) {
            retryBatchQr("本件扫码等待超时");
            return;
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        String observedPackage = root == null || root.getPackageName() == null ? "" : root.getPackageName().toString();
        if (root == null || observedPackage.equals(getPackageName())) {
            if (SystemClock.uptimeMillis() - navigationStageStarted > 18000) pause("请允许采集应用打开抖音商城，再继续批次");
            else schedule(750);
            if (root != null) root.recycle();
            return;
        }
        MallNavigator.Step decision;
        try { decision = MallNavigator.next(root, navigationStage, item); }
        finally { root.recycle(); }
        if (!decision.intervention.isEmpty()) {
            if (decision.intervention.contains("网络异常") || decision.intervention.contains("商品已下架")
                    || decision.intervention.contains("商品不存在") || decision.intervention.contains("商品已失效") || decision.intervention.contains("无法访问页面")) {
                if (!decision.intervention.equals(navigationError)) {
                    navigationError = decision.intervention;
                    navigationErrorStarted = SystemClock.uptimeMillis();
                    schedule(1300);
                    return;
                }
                if (SystemClock.uptimeMillis() - navigationErrorStarted < 1600) { schedule(800); return; }
                if (decision.intervention.contains("网络异常") && !refreshedProduct && decision.click != null) {
                    refreshedProduct = true;
                    navigationError = "";
                    clickNavigation(decision.click, observedPackage);
                    navigationStageStarted = SystemClock.uptimeMillis();
                    schedule(2000);
                    return;
                }
                retryBatchQr(decision.intervention);
            } else pause(decision.intervention);
            return;
        }
        navigationError = "";
        if (decision.ready) {
            activateBatchProduct(item, decision);
            return;
        }
        if (SystemClock.uptimeMillis() - navigationStageStarted > decision.timeoutMs) {
            retryBatchQr(decision.timeoutMessage.isEmpty() ? decision.message + "等待超时" : decision.timeoutMessage);
            return;
        }
        BatchStore.phase(this, decision.message, "scanning");
        publish("running", decision.message);
        if (decision.back) {
            if (!performGlobalAction(GLOBAL_ACTION_BACK)) { pause("商城未执行返回，请检查手机后继续"); return; }
            navigationAdvance(decision.nextStage);
            schedule(850);
        } else if (decision.click != null) {
            clickNavigation(decision.click, observedPackage);
            navigationAdvance(decision.nextStage);
            schedule(850);
        } else if (decision.needsScreenshot) {
            final String expectedNavigation = navigationStage;
            screenshot(bitmap -> {
                try {
                    if (!expectedNavigation.equals(navigationStage)) return;
                    Rect found = "scan_verify".equals(expectedNavigation)
                            ? MallNavigator.findQr(bitmap, item.optString("product_id"))
                            : MallNavigator.findControl(this, bitmap, expectedNavigation);
                    if (found == null) { schedule(900); return; }
                    clickNavigation(found, observedPackage);
                    navigationAdvance(decision.nextStage);
                    schedule(1100);
                } catch (Exception error) {
                    pause("扫码画面尚未核对：" + readableError(error));
                } finally { bitmap.recycle(); }
            });
        } else {
            navigationAdvance(decision.nextStage);
            schedule(650);
        }
    }

    private void navigationAdvance(String next) {
        if (next != null && !next.isEmpty() && !next.equals(navigationStage)) {
            navigationStage = next;
            navigationStageStarted = SystemClock.uptimeMillis();
        }
    }

    private void clickNavigation(Rect box, String expectedPackage) {
        android.util.Log.i("PhoneCollector", "navigation_click start " + expectedPackage);
        AccessibilityNodeInfo current = getRootInActiveWindow();
        try {
            if (current == null || current.getPackageName() == null || !expectedPackage.contentEquals(current.getPackageName())) {
                throw new IllegalStateException("扫码画面发生变化，请检查后继续");
            }
            if (!KaoguAppAdapter.PACKAGE.equals(expectedPackage)) {
                AccessibilityNodeInfo target = clickableAt(current, box);
                if (target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return;
            }
            Path path = new Path();
            path.moveTo(box.exactCenterX(), box.exactCenterY());
            if (!dispatchGesture(new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(path, 0, 85)).build(), null, handler)) {
                throw new IllegalStateException("系统未执行扫码点击");
            }
            android.util.Log.i("PhoneCollector", "navigation_click dispatched");
        } finally { if (current != null) current.recycle(); }
        android.util.Log.i("PhoneCollector", "navigation_click finished");
    }

    private void activateBatchProduct(JSONObject item, MallNavigator.Step verified) {
        boolean restore = sameBatchItem(record, item);
        if (!restore) {
            record = new JSONObject();
            put(record, "record_id", batchRecordId(item));
            put(record, "task_id", UUID.randomUUID().toString());
            put(record, "platform", PLATFORM);
            put(record, "platform_name", "抖音商城");
            put(record, "source_application", targetPackage);
            put(record, "capture_sku_images", captureSkuImages());
            put(record, "captured_at", Instant.now().toString());
            put(record, "skus", new JSONArray());
            put(record, "batch_id", item.optString("batch_id"));
            if (item.has("workbench_job_id")) put(record, "workbench_job_id", item.optString("workbench_job_id"));
            if (!item.optString("product_id").isEmpty()) put(record, "product_id", item.optString("product_id"));
            if (!item.optString("source_item_key").isEmpty()) put(record, "source_item_key", item.optString("source_item_key"));
            if (appSourceMode) {
                put(record, "source_mode", "kaogujia_app");
                put(record, "source_date_range", appDateRange);
                put(record, "source_card", item.optJSONObject("source_card"));
                put(record, "source_detail", item.optJSONObject("source_detail"));
            }
            put(record, "kaogujia_key", item.optString("kaogujia_key"));
            put(record, "source_title", item.optString("title"));
            put(record, "shop_name", item.optString("shop_name"));
            put(record, "source_url", item.optString("url"));
            put(record, "list_image_url", item.optString("list_image_url"));
            put(record, "main_image_url", item.optString("main_image_url"));
            if (item.has("main_image")) put(record, "main_image", item.optJSONObject("main_image"));
            put(record, "video_url", item.optString("video_url"));
            put(record, "video_status", item.optString("video_status"));
            if (item.has("detail_field_names")) put(record, "kaogujia_detail_fields", item.optJSONArray("detail_field_names"));
            combinations.clear();
            index = 0;
            collectionTruncated = dataIncomplete = false;
        }
        put(record, "kaogujia_product_name", item.optString("title"));
        put(record, "kaogujia_shop_name", item.optString("shop_name"));
        put(record, "douyin_product_name", DouyinAdapter.cleanProductTitle(verified.identityTitle, item.optString("title")));
        put(record, "identity_basis", verified.identityBasis);
        if (!appSourceMode) put(record, "qr_product_id_verified", true);
        currentSku = null;
        combinationIndex = -1;
        pageScrolls = 0;
        resuming = restore && !combinations.isEmpty();
        resumeTitleVerified = false;
        recycleImages();
        if (appSourceMode && !restore) {
            setStage("link_open", "正在读取抖音商品链接");
        } else {
            setStage("discover", "商品身份已核对，正在采集规格和报价");
        }
    }

    private void retryBatchQr(String error) {
        invalidateCaptures();
        if (qrRetries++ < 1) {
            cleanBatchQr(this::continueBatch);
            return;
        }
        finishBatchItem("failed", error);
    }

    private void skipBatchItem(String reason) {
        JSONArray skus = record == null ? null : record.optJSONArray("skus");
        finishBatchItem(skus != null && skus.length() > 0 ? "partial" : "failed", reason);
    }

    private void finishBatchItem(String status, String error) {
        invalidateCaptures();
        handler.removeCallbacks(step);
        recycleImages();
        currentSku = null;
        String savedId = "";
        try {
            if (record == null) {
                JSONObject item = BatchStore.current(this);
                if (item != null) {
                    record = new JSONObject();
                    put(record, "record_id", batchRecordId(item));
                    put(record, "task_id", UUID.randomUUID().toString());
                    put(record, "platform", PLATFORM);
                    put(record, "platform_name", "抖音商城");
                    put(record, "source_application", targetPackage);
                    put(record, "captured_at", Instant.now().toString());
                    put(record, "kaogujia_product_name", item.optString("title"));
                    put(record, "source_title", item.optString("title"));
                    put(record, "kaogujia_shop_name", item.optString("shop_name"));
                    put(record, "identity_basis", "商城详情尚未确认");
                    if (appSourceMode) {
                        put(record, "source_mode", "kaogujia_app");
                        put(record, "source_date_range", appDateRange);
                    }
                    put(record, "skus", new JSONArray());
                    for (String key : new String[]{"batch_id", "workbench_job_id", "product_id", "kaogujia_key", "source_item_key", "source_mode", "source_card", "source_detail", "title", "shop_name", "url", "list_image_url", "main_image_url", "main_image", "video_url", "video_status"}) {
                        if (item.has(key) && !"title".equals(key)) put(record, key, item.opt(key));
                    }
                }
            }
            if (record != null) {
                put(record, "status", status);
                put(record, "completed_at", Instant.now().toString());
                if (!error.isEmpty()) put(record, "collection_error", error);
                String invalid = BatchStore.invalidReason(error);
                if ("failed".equals(status) && !invalid.isEmpty()) {
                    put(record, "product_state", "invalid");
                    put(record, "invalid_reason", invalid);
                }
                savedId = RecordStore.save(this, record);
            }
            BatchStore.finishItem(this, status, savedId, error);
            new AtomicFile(new File(getFilesDir(), "active_task.json")).delete();
            record = null;
            combinations.clear();
            index = 0;
            cleanBatchQr(this::continueBatch);
        } catch (Exception failure) {
            pause("本件结果尚未保存，请保留批次后继续：" + readableError(failure));
        }
    }

    private void stopBatch() {
        stoppingBatch = true;
        invalidateCaptures();
        recycleImages();
        currentSku = null;
        try {
            if (record != null && record.optJSONArray("skus") != null && record.optJSONArray("skus").length() > 0) {
                put(record, "status", "stopped");
                put(record, "completed_at", Instant.now().toString());
                RecordStore.save(this, record);
            }
            BatchStore.stop(this, "批次已结束，已经采集的内容已保存");
            new AtomicFile(new File(getFilesDir(), "active_task.json")).delete();
            running = true;
            paused = false;
            cleanBatchQr(() -> {
                running = false;
                batchMode = false;
                stoppingBatch = false;
                record = null;
                stage = "idle";
                handler.removeCallbacks(step);
                publish("stopped", "批次已结束，临时二维码已清理");
            });
        } catch (Exception error) { pause("结束批次尚未完成：" + readableError(error)); }
    }

    private void completeBatch() {
        invalidateCaptures();
        running = false;
        paused = false;
        batchMode = false;
        stage = "idle";
        handler.removeCallbacks(step);
        JSONObject summary = BatchStore.summary(this);
        String message = "采集结束：已保存 " + summary.optInt("completed") + " 件，失败 " + summary.optInt("failed") + " 件";
        publish("complete", message);
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show();
        try {
            Intent results = new Intent(this, MainActivity.class).putExtra("open_results", true);
            results.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(results);
        } catch (Exception error) {
            android.util.Log.w("PhoneCollector", "系统未允许自动返回结果页", error);
        }
    }

    private void tickSafely() {
        if (!running || paused || capturing) {
            return;
        }
        try {
            tick();
        } catch (Exception error) {
            pause("采集暂时停止，进度已保存：" + readableError(error));
        }
    }

    private void tick() throws Exception {
        if (batchMode && stage.startsWith("batch_")) {
            tickBatch();
            return;
        }
        if ("link_clipboard".equals(stage)) {
            if (elapsed() > 5000) finishProductLink("");
            else schedule(450);
            return;
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getPackageName() == null || !targetPackage.contentEquals(root.getPackageName())) {
            if (root != null) {
                root.recycle();
            }
            if (elapsed() > 25000) {
                pause("请回到抖音商城的当前商品页面后继续");
            } else {
                schedule(600);
            }
            return;
        }
        if (stage.startsWith("shop_")) {
            try { collectShop(root); } finally { root.recycle(); }
            return;
        }
        if (stage.startsWith("link_")) {
            try { collectProductLink(root); } finally { root.recycle(); }
            return;
        }
        DouyinAdapter.Snapshot screen = DouyinAdapter.read(root);
        root.recycle();
        if (!screen.intervention.isEmpty()) {
            if (batchMode && DouyinAdapter.isUnavailable(screen.intervention)) skipBatchItem(screen.intervention);
            else pause(screen.intervention);
            return;
        }
        if (combinationIndex == index && combinationStarted > 0
                && !"discover".equals(stage) && !"list".equals(stage)
                && SystemClock.uptimeMillis() - combinationStarted > 75000) {
            pause("当前规格组合长时间未能核实，进度已保留，请检查原商品后继续");
            return;
        }
        switch (stage) {
            case "discover":
                discover(screen);
                return;
            case "list":
                readOptions(screen);
                return;
            case "select":
                selectCombination(screen);
                return;
            case "price":
                confirmPrice(screen);
                return;
            case "header":
                captureHeader(screen);
                return;
            case "gallery":
                captureGallery(screen);
                return;
            case "return":
                returnToSpecs(screen);
                return;
            default:
                pause("任务状态需要重新确认，请结束后重新采集当前商品");
        }
    }

    private void discover(DouyinAdapter.Snapshot screen) {
        if (resuming && !resumeTitleVerified) {
            String expected = record.optString("identity_title");
            if (expected.isEmpty()) {
                pause("上次任务没有可靠的原商品标题，无法安全续采，请结束后从商品详情页重新采集");
                return;
            }
            if (screen.popup || screen.gallery) {
                pause("请先返回原商品详情页，让商品标题可见后再继续");
                return;
            }
            if (screen.title.isEmpty()) {
                if (elapsed() > 8000) {
                    pause("未读取到原商品标题，请让原商品详情标题可见后继续");
                } else {
                    schedule(700);
                }
                return;
            }
            String sourceTitle = record.optString("source_title");
            if (!identityTitle(DouyinAdapter.cleanProductTitle(screen.title, sourceTitle))
                    .equals(identityTitle(DouyinAdapter.cleanProductTitle(expected, sourceTitle)))) {
                pause("当前商品标题与上次任务不一致，请回到原商品详情页后继续");
                return;
            }
            resumeTitleVerified = true;
        }
        if (!resuming && !screen.title.isEmpty()) {
            String title = DouyinAdapter.cleanProductTitle(screen.title, record.optString("source_title"));
            put(record, "title", title);
            put(record, "douyin_product_name", title);
            if (!screen.popup && !screen.gallery) {
                put(record, "identity_title", title);
                put(record, "identity_basis", "原生商品详情标题及规格签名");
            }
        }
        if (!screen.tags.isEmpty()) {
            put(record, "product_tags", new JSONArray(screen.tags));
        }
        if (screen.declaredCount > 0) {
            put(record, "sku_count", screen.declaredCount);
            put(record, "sku_count_source", "商品详情规格入口");
        }
        if (screen.popup) {
            setStage("list", "正在读取规格选项");
            return;
        }
        if (screen.openSpecs != null) {
            couponEntryTried = screen.openSpecs.equals(screen.couponSpecEntry);
            click(screen.openSpecs);
            setStage("list", "正在打开商品规格");
            return;
        }
        if (elapsed() > 15000) {
            pause("未识别到底部加购入口，已停止；商品详情页不会自动下滑");
        } else {
            schedule(700);
        }
    }

    private void readOptions(DouyinAdapter.Snapshot screen) {
        if (screen.smallModeControl != null) {
            if (++attempts > 3) {
                pause("未能切换到小图规格模式");
                return;
            }
            click(screen.smallModeControl);
            schedule(900);
            return;
        }
        if (!screen.popup || screen.groups.isEmpty()) {
            if (elapsed() > 8000) {
                String expected = record.optString("identity_title");
                if (!couponEntryTried && screen.couponSpecEntry != null && !expected.isEmpty()
                        && identityTitle(DouyinAdapter.cleanProductTitle(screen.title, record.optString("source_title")))
                                .equals(identityTitle(expected))) {
                    couponEntryTried = true;
                    click(screen.couponSpecEntry);
                    setStage("list", "正在通过领券购买打开商品规格");
                    return;
                }
                pause("请将商品规格弹窗保持打开后继续");
            } else {
                schedule(600);
            }
            return;
        }
        String signature = specificationSignature(screen);
        if (signature.isEmpty()) {
            pause("规格分组名称存在歧义，未能安全区分各组选项");
            return;
        }
        if (resuming) {
            if (!resumeTitleVerified || !signature.equals(record.optString("specification_signature"))) {
                pause("原商品规格与保存进度不一致，无法安全续采，请回原商品核对后继续");
                return;
            }
            resuming = false;
            combinationIndex = -1;
            setStage("select", "商品标题和规格已核对，正在继续第 " + (index + 1) + " 个组合");
            return;
        }
        put(record, "specification_signature", signature);
        if (record.optString("title").isEmpty()) {
            put(record, "title", "当前抖音商城商品");
        }
        if (screen.declaredCount > 0) {
            put(record, "sku_count", screen.declaredCount);
            put(record, "sku_count_source", "商品规格选项");
        }
        JSONArray definitions = new JSONArray();
        combinations.clear();
        combinations.add(new LinkedHashMap<>());
        groupNames.clear();
        int maximum = Math.max(1, appSourceMode ? BatchStore.settings(this).optInt("max_combinations", 24)
                : getSharedPreferences("collector_settings", MODE_PRIVATE).getInt("max_combinations", 24));
        for (DouyinAdapter.Group group : screen.groups) {
            groupNames.add(group.name);
            JSONArray names = new JSONArray();
            for (DouyinAdapter.Option option : group.options) {
                names.put(option.name);
            }
            JSONObject definition = new JSONObject();
            put(definition, "name", group.name);
            put(definition, "declared_count", group.declaredCount);
            put(definition, "options", names);
            definitions.put(definition);
            if (group.declaredCount > group.options.size()) {
                collectionTruncated = true;
            }
            List<LinkedHashMap<String, String>> next = new ArrayList<>();
            for (LinkedHashMap<String, String> previous : combinations) {
                for (DouyinAdapter.Option option : group.options) {
                    if (next.size() >= maximum) {
                        collectionTruncated = true;
                        break;
                    }
                    LinkedHashMap<String, String> candidate = new LinkedHashMap<>(previous);
                    candidate.put(group.name, option.name);
                    next.add(candidate);
                }
            }
            combinations.clear();
            combinations.addAll(next);
        }
        put(record, "specification_groups", definitions);
        put(record, "combination_count", combinations.size());
        if (collectionTruncated) {
            put(record, "coverage_note", "规格数量超过本次可确认范围，已保留当前可读取组合");
        }
        if (combinations.isEmpty()) {
            pause("没有可读取的规格组合");
            return;
        }
        index = 0;
        setStage("select", "正在选择第 1 个规格组合");
    }

    private void selectCombination(DouyinAdapter.Snapshot screen) {
        if (index >= combinations.size()) {
            finish(resultStatus(), "当前商品采集完成，结果已保存在手机");
            return;
        }
        if (combinationIndex != index) {
            combinationIndex = index;
            combinationStarted = SystemClock.uptimeMillis();
            selectionChangedAt = combinationStarted;
            selectionClicks = 0;
            priceRestarts = 0;
            blockedSamples = 0;
            blockedSelection = "";
            currentSku = null;
            acceptedPrice = "";
        }
        if (!screen.popup) {
            if (screen.gallery && attempts < 2) {
                attempts++;
                performGlobalAction(GLOBAL_ACTION_BACK);
                schedule(700);
                return;
            }
            pause("规格弹窗已关闭，请回到原商品规格后继续");
            return;
        }
        LinkedHashMap<String, String> desired = combinations.get(index);
        int blocked = 0;
        StringBuilder context = new StringBuilder();
        DouyinAdapter.Option nextOption = null;
        for (Map.Entry<String, String> value : desired.entrySet()) {
            DouyinAdapter.Group group = findGroup(screen, value.getKey());
            DouyinAdapter.Option option = findOption(group, value.getValue());
            if (option == null) {
                pause("当前画面未找到“" + value.getValue() + "”，请将该规格滚动到可见位置后继续");
                return;
            }
            context.append(value.getKey()).append('=').append(value.getValue()).append(':')
                    .append(option.enabled).append(':').append(option.selected).append(';');
            if (!option.enabled) {
                blocked++;
            } else if (!option.selected && nextOption == null) {
                nextOption = option;
            }
        }
        // A disabled option may be restricted by the previous combination. First
        // move every other target dimension that is currently selectable.
        if (nextOption != null) {
            if (++selectionClicks > 14) {
                pause("规格选择多次互相影响，未能确认目标组合，进度已保留");
                return;
            }
            selectionChangedAt = SystemClock.uptimeMillis();
            blockedSamples = 0;
            blockedSelection = "";
            click(nextOption.bounds);
            schedule(850);
            return;
        }
        if (blocked > 0) {
            if (SystemClock.uptimeMillis() - selectionChangedAt < 2500) {
                schedule(700);
                return;
            }
            String observedContext = context.toString();
            blockedSamples = observedContext.equals(blockedSelection) ? blockedSamples + 1 : 1;
            blockedSelection = observedContext;
            if (blockedSamples < 3) {
                schedule(750);
                return;
            }
            currentSku = sku(desired);
            if (blocked == 1 && selectedDimensionsUnique(screen, desired, true)) {
                put(currentSku, "available", false);
                put(currentSku, "note", "其他目标维度已选中，目标选项连续确认不可选");
            } else {
                put(currentSku, "availability_note", "多个目标维度互相限制，未能确认是否可售");
            }
            appendSku();
            return;
        }
        if (!sameSelection(screen)) {
            pause("规格存在多个选中状态，未能确认唯一的目标组合");
            return;
        }
        currentSku = sku(desired);
        boolean hot = false;
        boolean hotPrice = false;
        for (Map.Entry<String, String> value : desired.entrySet()) {
            DouyinAdapter.Option option = findOption(findGroup(screen, value.getKey()), value.getValue());
            hot |= option != null && option.hot;
            hotPrice |= option != null && option.hotPrice;
        }
        put(currentSku, "available", true);
        put(currentSku, "hot", hot);
        if (hotPrice) put(currentSku, "promotion_label", "火爆价");
        stablePrice = "";
        stableSamples = 0;
        stableSince = 0;
        setStage("price", "正在核对第 " + (index + 1) + " 个组合的顶部报价");
    }

    private void confirmPrice(DouyinAdapter.Snapshot screen) {
        if (!sameSelection(screen)) {
            restartCombination("规格选择发生变化，正在重新核对价图");
            return;
        }
        if (SystemClock.uptimeMillis() - selectionChangedAt < 2500) {
            schedule(650);
            return;
        }
        String observed = screen.price == null ? "{}" : screen.price.toString();
        long now = SystemClock.uptimeMillis();
        if (observed.equals(stablePrice)) {
            stableSamples++;
        } else {
            stablePrice = observed;
            stableSince = now;
            stableSamples = 1;
        }
        if (!"{}".equals(observed) && stableSamples >= 3 && now - stableSince >= 1500) {
            Iterator<String> keys = screen.price.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                put(currentSku, key, screen.price.opt(key));
            }
            acceptedPrice = observed;
            if (!captureSkuImages()) {
                put(currentSku, "image_status", "not_requested");
                appendSku();
                return;
            }
            setStage("header", "正在保存当前规格的完整图片");
            return;
        }
        if (elapsed() >= 12000) {
            put(currentSku, "price_note", "选中后的顶部精确报价尚未核实");
            acceptedPrice = "";
            if (!captureSkuImages()) {
                put(currentSku, "image_status", "not_requested");
                appendSku();
                return;
            }
            setStage("header", "报价已标记待核对，正在读取对应图片");
        } else {
            schedule(650);
        }
    }

    private void captureHeader(DouyinAdapter.Snapshot screen) {
        if (!sameSelection(screen)) {
            restartCombination("正在重新核对当前规格和价图");
            return;
        }
        if (priceChanged(screen)) {
            restartCombination("规格报价更新，正在重新核对价图");
            return;
        }
        if (screen.headerImage == null) {
            put(currentSku, "image_note", "未定位到已选规格的顶部图片");
            setStage("return", "正在最后核对当前规格报价");
            return;
        }
        imageBounds = new Rect(screen.headerImage);
        final Rect requestedBounds = new Rect(imageBounds);
        screenshot(bitmap -> {
            if (!running || paused || !"header".equals(stage)) {
                bitmap.recycle();
                return;
            }
            DouyinAdapter.Snapshot current = currentScreen();
            if (current == null || !sameSelection(current)) {
                bitmap.recycle();
                pause("截图时原规格画面发生变化，请返回原商品后继续");
                return;
            }
            if (priceChanged(current)) {
                bitmap.recycle();
                restartCombination("截图时报价更新，正在重新核对");
                return;
            }
            Rect safe = new Rect(requestedBounds);
            if (!safe.intersect(0, 0, bitmap.getWidth(), bitmap.getHeight()) || safe.width() < 30 || safe.height() < 30) {
                bitmap.recycle();
                put(currentSku, "image_note", "顶部图片边界无法核对");
                setStage("return", "正在最后核对当前规格报价");
                return;
            }
            Bitmap sample = Bitmap.createBitmap(bitmap, safe.left, safe.top, safe.width(), safe.height());
            bitmap.recycle();
            if (thumbnail == null) {
                thumbnail = sample;
                schedule(1200);
                return;
            }
            double difference = imageDifference(thumbnail, sample);
            thumbnail.recycle();
            thumbnail = sample;
            if (difference > 4 && ++attempts < 3) {
                schedule(1200);
                return;
            }
            if (difference > 4) {
                put(currentSku, "image_note", "顶部图片仍在更新");
                setStage("return", "正在最后核对当前规格报价");
                return;
            }
            click(requestedBounds);
            setStage("gallery", "已打开当前规格图片，正在保存完整预览");
        });
    }

    private void captureGallery(DouyinAdapter.Snapshot screen) {
        if (!screen.gallery) {
            if (elapsed() < 4500) {
                schedule(700);
            } else if (sameSelection(screen)) {
                put(currentSku, "image_note", "未确认完整图片预览已打开");
                setStage("return", "正在最后核对当前规格报价");
            } else {
                pause("未能确认图片预览所在页面，请回原商品后继续");
            }
            return;
        }
        screenshot(bitmap -> {
            if (!running || paused || !"gallery".equals(stage)) {
                bitmap.recycle();
                return;
            }
            DouyinAdapter.Snapshot current = currentScreen();
            if (current == null || !current.gallery) {
                bitmap.recycle();
                pause("图片预览画面发生变化，请回原商品后继续");
                return;
            }
            Bitmap cropped = cropGallery(bitmap);
            bitmap.recycle();
            if (cropped == null) {
                if (++attempts < 3) {
                    schedule(1200);
                    return;
                }
                put(currentSku, "image_note", "未读到可核对的完整图片预览");
                performGlobalAction(GLOBAL_ACTION_BACK);
                setStage("return", "正在返回规格列表");
                return;
            }
            if (thumbnail == null || !imagesMatch(thumbnail, cropped)) {
                cropped.recycle();
                if (++attempts < 3) {
                    schedule(1200);
                    return;
                }
                put(currentSku, "image_note", "完整预览与已选规格的顶部图片未匹配");
            } else {
                preview = cropped;
            }
            performGlobalAction(GLOBAL_ACTION_BACK);
            setStage("return", "正在核对图片与规格的对应关系");
        });
    }

    private void returnToSpecs(DouyinAdapter.Snapshot screen) {
        if (!sameSelection(screen)) {
            if (++attempts < 4) {
                schedule(650);
            } else {
                pause("返回列表后未能核对原规格组合，进度已保留");
            }
            return;
        }
        if (priceChanged(screen)) {
            restartCombination("返回规格后发现报价变化，正在重新采集该组合");
            return;
        }
        String observed = screen.price == null ? "{}" : screen.price.toString();
        long now = SystemClock.uptimeMillis();
        if (!observed.equals(returnPrice)) {
            returnPrice = observed;
            returnStableSince = now;
            schedule(800);
            return;
        }
        if (now - returnStableSince < 750) {
            schedule(800);
            return;
        }
        if (preview == null) {
            savePreviewAndAppend();
            return;
        }
        if (screen.headerImage == null || thumbnail == null) {
            recyclePreview();
            put(currentSku, "image_note", "返回后未能再次确认对应的顶部图片");
            savePreviewAndAppend();
            return;
        }
        final Rect returnedBounds = new Rect(screen.headerImage);
        screenshot(bitmap -> {
            DouyinAdapter.Snapshot current = currentScreen();
            if (current == null || !sameSelection(current)) {
                bitmap.recycle();
                pause("提交前规格画面发生变化，进度已保留");
                return;
            }
            if (priceChanged(current)) {
                bitmap.recycle();
                restartCombination("提交前报价变化，正在重新采集该组合");
                return;
            }
            Rect safe = new Rect(returnedBounds);
            if (!safe.intersect(0, 0, bitmap.getWidth(), bitmap.getHeight()) || safe.width() < 30 || safe.height() < 30) {
                bitmap.recycle();
                recyclePreview();
                put(currentSku, "image_note", "提交前图片边界无法核实");
                savePreviewAndAppend();
                return;
            }
            Bitmap currentImage = Bitmap.createBitmap(bitmap, safe.left, safe.top, safe.width(), safe.height());
            bitmap.recycle();
            double difference = imageDifference(thumbnail, currentImage);
            currentImage.recycle();
            if (difference > 4) {
                restartCombination("返回后规格图片更新，正在重新采集价图");
                return;
            }
            savePreviewAndAppend();
        });
    }

    private void savePreviewAndAppend() {
        if (preview != null) {
            try {
                File directory = new File(getFilesDir(), "images/" + record.optString("task_id"));
                if (!directory.isDirectory() && !directory.mkdirs()) {
                    throw new IllegalStateException("无法创建图片目录");
                }
                File file = new File(directory, String.format(java.util.Locale.ROOT, "%04d.png", index + 1));
                AtomicFile output = new AtomicFile(file);
                FileOutputStream stream = output.startWrite();
                try {
                    if (!preview.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                        throw new IllegalStateException("图片保存失败");
                    }
                    output.finishWrite(stream);
                } catch (Exception error) {
                    output.failWrite(stream);
                    throw error;
                }
                JSONObject image = new JSONObject();
                put(image, "file", file.getAbsolutePath());
                put(image, "width", preview.getWidth());
                put(image, "height", preview.getHeight());
                put(image, "source", "抖音商城已选规格顶部图片预览裁切");
                put(currentSku, "image", image);
            } catch (Exception error) {
                put(currentSku, "image_note", "图片文件未能保存");
            }
        }
        appendSku();
    }

    private boolean sameSelection(DouyinAdapter.Snapshot screen) {
        if (!screen.popup || index >= combinations.size()) {
            return false;
        }
        if (screen.groups.size() != combinations.get(index).size()) {
            return false;
        }
        for (Map.Entry<String, String> value : combinations.get(index).entrySet()) {
            DouyinAdapter.Group group = findGroup(screen, value.getKey());
            DouyinAdapter.Option option = findOption(group, value.getValue());
            if (option == null || !option.enabled || !option.selected) {
                return false;
            }
            int selected = 0;
            for (DouyinAdapter.Option candidate : group.options) {
                selected += candidate.selected ? 1 : 0;
            }
            if (selected != 1) {
                return false;
            }
        }
        return true;
    }

    private static boolean selectedDimensionsUnique(DouyinAdapter.Snapshot screen,
            LinkedHashMap<String, String> desired, boolean skipDisabled) {
        if (screen.groups.size() != desired.size()) {
            return false;
        }
        for (Map.Entry<String, String> entry : desired.entrySet()) {
            DouyinAdapter.Group group = findGroup(screen, entry.getKey());
            DouyinAdapter.Option option = findOption(group, entry.getValue());
            if (option == null) {
                return false;
            }
            if (!option.enabled && skipDisabled) {
                continue;
            }
            int selected = 0;
            for (DouyinAdapter.Option candidate : group.options) {
                selected += candidate.selected ? 1 : 0;
            }
            if (!option.selected || selected != 1) {
                return false;
            }
        }
        return true;
    }

    private boolean priceChanged(DouyinAdapter.Snapshot screen) {
        return !acceptedPrice.isEmpty()
                && !acceptedPrice.equals(screen.price == null ? "{}" : screen.price.toString());
    }

    private void restartCombination(String message) {
        if (++priceRestarts > 2) {
            pause("当前规格的价格或图片持续变化，未确认的数据不会入库，请稍后继续");
            return;
        }
        recycleImages();
        currentSku = null;
        acceptedPrice = "";
        stablePrice = "";
        selectionChangedAt = SystemClock.uptimeMillis();
        setStage("select", message);
    }

    private DouyinAdapter.Snapshot currentScreen() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            return null;
        }
        try {
            if (root.getPackageName() == null || !targetPackage.contentEquals(root.getPackageName())) {
                return null;
            }
            return DouyinAdapter.read(root);
        } finally {
            root.recycle();
        }
    }

    private static String identityTitle(String title) {
        return DouyinAdapter.normalize(title).replaceAll("\\s+", "");
    }

    private static String specificationSignature(DouyinAdapter.Snapshot screen) {
        TreeMap<String, TreeSet<String>> groups = new TreeMap<>();
        for (DouyinAdapter.Group group : screen.groups) {
            if (groups.containsKey(group.name)) {
                return "";
            }
            TreeSet<String> options = new TreeSet<>();
            for (DouyinAdapter.Option option : group.options) {
                options.add(option.name);
            }
            groups.put(group.name, options);
        }
        JSONArray signature = new JSONArray();
        for (Map.Entry<String, TreeSet<String>> group : groups.entrySet()) {
            JSONArray entry = new JSONArray();
            entry.put(group.getKey());
            entry.put(new JSONArray(group.getValue()));
            signature.put(entry);
        }
        return signature.toString();
    }

    private String resultStatus() {
        return collectionTruncated || dataIncomplete ? "partial" : "complete";
    }

    private boolean captureSkuImages() { return false; }

    private JSONObject sku(LinkedHashMap<String, String> desired) {
        JSONObject result = new JSONObject();
        JSONObject specifications = new JSONObject();
        List<String> names = new ArrayList<>();
        for (Map.Entry<String, String> entry : desired.entrySet()) {
            put(specifications, entry.getKey(), entry.getValue());
            names.add(entry.getValue());
        }
        put(result, "name", android.text.TextUtils.join(" / ", names));
        put(result, "specifications", specifications);
        return result;
    }

    private void appendSku() {
        if (!running || paused || currentSku == null) {
            return;
        }
        JSONArray missing = new JSONArray();
        if (!currentSku.has("price_before_coupon_fen")) {
            missing.put("price_before_coupon_fen");
        }
        if (!currentSku.has("price_after_coupon_fen")) {
            missing.put("price_after_coupon_fen");
        }
        if (captureSkuImages() && !currentSku.has("image")) {
            missing.put("image");
        }
        if (missing.length() > 0) {
            dataIncomplete = true;
            put(currentSku, "missing_fields", missing);
            put(currentSku, "data_complete", false);
        } else {
            put(currentSku, "data_complete", true);
        }
        record.optJSONArray("skus").put(currentSku);
        currentSku = null;
        recycleImages();
        index++;
        checkpoint();
        if (index >= combinations.size()) {
            finish(resultStatus(), dataIncomplete || collectionTruncated
                    ? "当前商品已保存，部分价图或规格未完整核实" : "当前商品采集完成，结果已保存在手机");
        } else {
            setStage("select", "正在选择第 " + (index + 1) + " 个规格组合");
        }
    }

    static void acceptProductLink(String taskId, String link) {
        CollectorService service = instance;
        if (service != null) service.handler.post(() -> {
            if (service.running && !service.paused && "link_clipboard".equals(service.stage) && service.record != null
                    && service.record.optString("task_id").equals(taskId)) service.finishProductLink(link);
        });
    }

    private void collectProductLink(AccessibilityNodeInfo root) {
        DouyinAdapter.ShareControls controls = DouyinAdapter.readShareControls(root);
        if (controls.copy != null) {
            productLinkCopiedAfter = System.currentTimeMillis();
            click(controls.copy);
            String taskId = record.optString("task_id");
            setStage("link_clipboard", "正在保存抖音分享链接");
            handler.postDelayed(() -> {
                if (!running || paused || !"link_clipboard".equals(stage) || record == null
                        || !taskId.equals(record.optString("task_id"))) return;
                try {
                    Intent read = new Intent(this, ProductLinkActivity.class);
                    read.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
                    read.putExtra("task_id", taskId);
                    read.putExtra("copied_after", productLinkCopiedAfter);
                    startActivity(read);
                } catch (RuntimeException error) {
                    finishProductLink("");
                }
            }, 500);
        } else if (elapsed() > 4500) {
            if (controls.sheet) performGlobalAction(GLOBAL_ACTION_BACK);
            finishProductLink("");
        } else if ("link_open".equals(stage) && controls.share != null && !controls.sheet) {
            click(controls.share);
            setStage("link_sheet", "正在打开抖音商品分享菜单");
        } else schedule(500);
    }

    private void finishProductLink(String link) {
        put(record, "product_link_status", link.isEmpty() ? "unavailable" : "captured");
        if (!link.isEmpty()) {
            put(record, "douyin_share_url", link);
            if (record.optString("source_url").isEmpty()) put(record, "source_url", link);
            String productId = MallNavigator.productId(link);
            if (productId != null && record.optString("product_id").isEmpty()) put(record, "product_id", productId);
        }
        setStage("discover", "正在读取商品规格和报价");
    }

    private void collectShop(AccessibilityNodeInfo root) {
        DouyinAdapter.ShopSnapshot shop = DouyinAdapter.readShop(root);
        if (shop.shopPage && !shop.name.isEmpty()) {
            put(record, "douyin_shop_name", shop.name);
            performGlobalAction(GLOBAL_ACTION_BACK);
            finishBatchItem(resultStatus(), "");
        } else if (elapsed() > 8000) {
            String error = shop.shopPage ? "已打开抖音店铺页，未识别店铺名称"
                    : "shop_name".equals(stage) ? "已点击进店，未识别抖音店铺页"
                    : "未找到左下角进店入口";
            finishBatchItem("partial", "规格已保存，" + error);
        } else if ("shop_close".equals(stage)) {
            if (DouyinAdapter.read(root).popup) performGlobalAction(GLOBAL_ACTION_BACK);
            setStage("shop_entry", "正在查找左下角进店入口");
        } else if ("shop_entry".equals(stage) && shop.entry != null) {
            clickNavigation(shop.entry, targetPackage);
            setStage("shop_name", "正在读取抖音店铺名称");
        } else schedule(500);
    }

    private void finish(String status, String message) {
        if (batchMode && appSourceMode && ("complete".equals(status) || "partial".equals(status))) {
            setStage("shop_close", "规格已采集，正在读取商城店铺");
            return;
        }
        if (batchMode) {
            finishBatchItem(status, "");
            return;
        }
        running = false;
        paused = false;
        invalidateCaptures();
        handler.removeCallbacks(step);
        recycleImages();
        put(record, "status", status);
        put(record, "completed_at", Instant.now().toString());
        try {
            RecordStore.save(this, record);
            new AtomicFile(new File(getFilesDir(), "active_task.json")).delete();
            stage = "idle";
            publish(status, message);
        } catch (Exception error) {
            running = true;
            paused = true;
            checkpoint();
            publish("paused", "结果尚未写入，请保留应用并继续：" + readableError(error));
        }
    }

    private void pause(String message) {
        pausedStage = stage;
        paused = true;
        invalidateCaptures();
        handler.removeCallbacks(step);
        if (batchMode) {
            try { BatchStore.pause(this, message); } catch (Exception ignored) { }
        }
        publish("paused", message);
        checkpoint();
    }

    private void setStage(String value, String message) {
        stage = value;
        stageStarted = SystemClock.uptimeMillis();
        attempts = 0;
        if ("return".equals(value)) {
            returnPrice = "";
            returnStableSince = 0;
        }
        publish("running", message);
        if (batchMode && !value.startsWith("batch_")) BatchStore.phase(this, message, "collecting");
        checkpoint();
        schedule(550);
    }

    private long elapsed() {
        return SystemClock.uptimeMillis() - stageStarted;
    }

    private void publish(String status, String message) {
        if ("running".equals(status) && !keepAlive) {
            startForegroundService(new Intent(this, CollectionKeepAliveService.class));
            keepAlive = true;
        } else if (!"running".equals(status) && keepAlive) {
            stopService(new Intent(this, CollectionKeepAliveService.class));
            keepAlive = false;
        }
        getSharedPreferences("collector_state", MODE_PRIVATE).edit()
                .putString("status", status).putString("phase", message).putString("platform", PLATFORM)
                .putString("progress", index + " / " + combinations.size() + " 个组合")
                .putString("task_id", record == null ? "" : record.optString("task_id")).commit();
    }

    private void schedule(long delay) {
        handler.removeCallbacks(step);
        if (running && !paused) {
            handler.postDelayed(step, delay);
        }
    }

    private void click(Rect rectangle) {
        if (rectangle == null || rectangle.isEmpty()) {
            throw new IllegalArgumentException("按钮位置为空");
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || !targetPackage.contentEquals(root.getPackageName())) {
            throw new IllegalStateException("请保持抖音商城在前台");
        }
        AccessibilityNodeInfo target = clickableAt(root, rectangle);
        if (target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return;
        }
        Path path = new Path();
        path.moveTo(rectangle.exactCenterX(), rectangle.exactCenterY());
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 85)).build();
        if (!dispatchGesture(gesture, null, handler)) {
            throw new IllegalStateException("系统未执行规格点击");
        }
    }

    private AccessibilityNodeInfo clickableAt(AccessibilityNodeInfo node, Rect wanted) {
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (node.isClickable() && node.isEnabled() && bounds.equals(wanted)) {
            return node;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                AccessibilityNodeInfo found = clickableAt(child, wanted);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private Rect rootBounds() {
        Rect bounds = new Rect();
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null) {
            root.getBoundsInScreen(bounds);
        }
        if (bounds.isEmpty()) {
            bounds.set(0, 0, getResources().getDisplayMetrics().widthPixels,
                    getResources().getDisplayMetrics().heightPixels);
        }
        return bounds;
    }

    private void swipe(int x1, int y1, int x2, int y2) {
        Path path = new Path();
        path.moveTo(x1, y1);
        path.lineTo(x2, y2);
        dispatchGesture(new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 420)).build(), null, handler);
    }

    private interface BitmapResult {
        void accept(Bitmap bitmap);
    }

    private void invalidateCaptures() {
        generation++;
        screenshotSequence++;
        capturing = false;
    }

    private boolean captureCurrent(long expectedGeneration, long expectedSequence,
            String expectedTask, int expectedIndex, String expectedStage) {
        return running && !paused && expectedGeneration == generation
                && expectedSequence == screenshotSequence
                && expectedTask.equals(record == null ? "" : record.optString("task_id"))
                && index == expectedIndex && stage.equals(expectedStage);
    }

    private void screenshotFailed(int errorCode) {
        if (errorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT && attempts++ < 3) {
            schedule(1250);
            return;
        }
        if (currentSku == null) {
            pause("系统截图未成功，错误码 " + errorCode);
            return;
        }
        put(currentSku, "image_note", "系统截图未成功，错误码 " + errorCode);
        recyclePreview();
        DouyinAdapter.Snapshot current = currentScreen();
        if (current != null && current.gallery) {
            performGlobalAction(GLOBAL_ACTION_BACK);
            setStage("return", "图片未保存，正在核对当前规格和报价");
        } else if (current != null && sameSelection(current)) {
            setStage("return", "图片未保存，正在核对当前规格和报价");
        } else {
            pause("截图失败且原商品画面无法确认，进度已保留");
        }
    }

    private void screenshot(BitmapResult callback) {
        if (!running || paused || capturing) {
            return;
        }
        if (SystemClock.uptimeMillis() < nextShot) {
            schedule(Math.max(300, nextShot - SystemClock.uptimeMillis()));
            return;
        }
        capturing = true;
        final long expectedGeneration = generation;
        final long expectedSequence = ++screenshotSequence;
        final String expectedTask = record == null ? "" : record.optString("task_id");
        final int expectedIndex = index;
        final String expectedStage = stage;
        nextShot = SystemClock.uptimeMillis() + 1150;
        takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
            @Override
            public void onSuccess(ScreenshotResult result) {
                if (!captureCurrent(expectedGeneration, expectedSequence, expectedTask, expectedIndex, expectedStage)) {
                    result.getHardwareBuffer().close();
                    return;
                }
                capturing = false;
                Bitmap copy = null;
                Bitmap hardware = null;
                try {
                    hardware = Bitmap.wrapHardwareBuffer(result.getHardwareBuffer(), result.getColorSpace());
                    copy = hardware == null ? null : hardware.copy(Bitmap.Config.ARGB_8888, false);
                    if (copy == null) {
                        pause("系统未返回可保存的图片");
                        return;
                    }
                    if (!batchMode || !"batch_scan".equals(stage)) {
                        DouyinAdapter.Snapshot current = currentScreen();
                        if (current == null) {
                            copy.recycle();
                            pause("截图期间抖音商城离开前台，进度已保留");
                            return;
                        }
                        if (!current.intervention.isEmpty()) {
                            copy.recycle();
                            pause(current.intervention);
                            return;
                        }
                    }
                    callback.accept(copy);
                } catch (Exception error) {
                    if (copy != null && !copy.isRecycled()) {
                        copy.recycle();
                    }
                    if (captureCurrent(expectedGeneration, expectedSequence, expectedTask, expectedIndex, expectedStage)) {
                        pause("图片核对暂时停止，进度已保留：" + readableError(error));
                    }
                } finally {
                    if (hardware != null) {
                        hardware.recycle();
                    }
                    result.getHardwareBuffer().close();
                }
            }

            @Override
            public void onFailure(int errorCode) {
                if (!captureCurrent(expectedGeneration, expectedSequence, expectedTask, expectedIndex, expectedStage)) {
                    return;
                }
                capturing = false;
                try {
                    screenshotFailed(errorCode);
                } catch (Exception error) {
                    pause("截图处理暂时停止，进度已保留：" + readableError(error));
                }
            }
        });
    }

    private static double imageDifference(Bitmap left, Bitmap right) {
        Bitmap a = Bitmap.createScaledBitmap(left, 38, 38, true);
        Bitmap b = Bitmap.createScaledBitmap(right, 38, 38, true);
        double sum = 0;
        for (int y = 4; y < 34; y++) {
            for (int x = 4; x < 34; x++) {
                int p = a.getPixel(x, y);
                int q = b.getPixel(x, y);
                sum += Math.abs(Color.red(p) - Color.red(q)) + Math.abs(Color.green(p) - Color.green(q))
                        + Math.abs(Color.blue(p) - Color.blue(q));
            }
        }
        a.recycle();
        b.recycle();
        return sum / (30 * 30 * 3);
    }

    private static boolean imagesMatch(Bitmap left, Bitmap right) {
        if (imageDifference(left, right) > 14) {
            return false;
        }
        Bitmap a = Bitmap.createScaledBitmap(left, 38, 38, true);
        Bitmap b = Bitmap.createScaledBitmap(right, 38, 38, true);
        try {
            // Check local regions as well as the overall average: a different
            // product colour can otherwise disappear into a shared white layout.
            for (int tileY = 0; tileY < 3; tileY++) {
                for (int tileX = 0; tileX < 3; tileX++) {
                    double sum = 0;
                    for (int y = 4 + tileY * 10; y < 14 + tileY * 10; y++) {
                        for (int x = 4 + tileX * 10; x < 14 + tileX * 10; x++) {
                            int p = a.getPixel(x, y);
                            int q = b.getPixel(x, y);
                            sum += Math.abs(Color.red(p) - Color.red(q))
                                    + Math.abs(Color.green(p) - Color.green(q))
                                    + Math.abs(Color.blue(p) - Color.blue(q));
                        }
                    }
                    if (sum / 300 > 28) {
                        return false;
                    }
                }
            }
            return true;
        } finally {
            a.recycle();
            b.recycle();
        }
    }

    private static Bitmap cropGallery(Bitmap frame) {
        int width = frame.getWidth();
        int height = frame.getHeight();
        if (bright(frame.getPixel(12 * width / 1080, 280 * height / 2400)) > 30
                || bright(frame.getPixel(1068 * width / 1080, 1870 * height / 2400)) > 30) {
            return null;
        }
        int bestStart = -1;
        int bestEnd = -1;
        int start = -1;
        int last = -1;
        int gap = Math.max(8, 28 * height / 2400);
        for (int y = 325 * height / 2400; y < 2010 * height / 2400; y++) {
            int visible = 0;
            for (int sample = 0; sample < 11; sample++) {
                int x = Math.min(width - 1, (35 + sample * 100) * width / 1080);
                visible += bright(frame.getPixel(x, y)) > 24 ? 1 : 0;
            }
            if (visible < 5) {
                continue;
            }
            if (start < 0 || y - last >= gap) {
                if (last - start > bestEnd - bestStart) {
                    bestStart = start;
                    bestEnd = last + 1;
                }
                start = y;
            }
            last = y;
        }
        if (last - start > bestEnd - bestStart) {
            bestStart = start;
            bestEnd = last + 1;
        }
        if (bestStart < 0 || bestEnd - bestStart < height * .15
                || bestEnd - bestStart > height * .64 || bestStart > height * .49 || bestEnd < height * .49) {
            return null;
        }
        if (Math.abs(bestEnd - bestStart - width) <= width * .01) {
            bestStart = (bestStart + bestEnd - width) / 2;
            bestEnd = bestStart + width;
        }
        return Bitmap.createBitmap(frame, 0, bestStart, width, bestEnd - bestStart);
    }

    private static int bright(int color) {
        return Math.max(Color.red(color), Math.max(Color.green(color), Color.blue(color)));
    }

    private static DouyinAdapter.Group findGroup(DouyinAdapter.Snapshot screen, String name) {
        for (DouyinAdapter.Group group : screen.groups) {
            if (group.name.equals(name)) {
                return group;
            }
        }
        return null;
    }

    private static DouyinAdapter.Option findOption(DouyinAdapter.Group group, String name) {
        if (group != null) {
            for (DouyinAdapter.Option option : group.options) {
                if (option.name.equals(name)) {
                    return option;
                }
            }
        }
        return null;
    }

    private void checkpoint() {
        if (record == null || !running || batchMode && stage.startsWith("batch_")) {
            return;
        }
        try {
            JSONObject state = new JSONObject();
            put(state, "record", record);
            put(state, "batch_mode", batchMode);
            put(state, "target_package", targetPackage);
            put(state, "stage", stage);
            put(state, "paused_stage", pausedStage);
            put(state, "index", index);
            put(state, "truncated", collectionTruncated);
            put(state, "data_incomplete", dataIncomplete);
            JSONArray choices = new JSONArray();
            for (LinkedHashMap<String, String> choice : combinations) {
                choices.put(new JSONObject(choice));
            }
            put(state, "combinations", choices);
            AtomicFile file = new AtomicFile(new File(getFilesDir(), "active_task.json"));
            FileOutputStream stream = file.startWrite();
            try {
                stream.write(state.toString().getBytes(StandardCharsets.UTF_8));
                file.finishWrite(stream);
            } catch (Exception error) {
                file.failWrite(stream);
                throw error;
            }
        } catch (Exception error) {
            paused = true;
            handler.removeCallbacks(step);
            if (batchMode) {
                try { BatchStore.pause(this, "暂时无法保存任务进度，请保留当前应用"); } catch (Exception ignored) { }
            }
            publish("paused", "暂时无法保存任务进度，请保留当前应用");
        }
    }

    private void loadCheckpoint() {
        AtomicFile file = new AtomicFile(new File(getFilesDir(), "active_task.json"));
        if (!file.getBaseFile().isFile()) {
            return;
        }
        try {
            JSONObject saved = new JSONObject(new String(file.readFully(), StandardCharsets.UTF_8));
            record = saved.getJSONObject("record");
            batchMode = saved.optBoolean("batch_mode");
            String restoredTarget = saved.optString("target_package", MALL_PACKAGE);
            targetPackage = "com.ss.android.ugc.aweme".equals(restoredTarget) ? restoredTarget : MALL_PACKAGE;
            index = saved.optInt("index");
            collectionTruncated = saved.optBoolean("truncated");
            dataIncomplete = saved.optBoolean("data_incomplete");
            combinations.clear();
            JSONArray choices = saved.optJSONArray("combinations");
            if (choices != null) {
                for (int i = 0; i < choices.length(); i++) {
                    JSONObject choice = choices.getJSONObject(i);
                    LinkedHashMap<String, String> values = new LinkedHashMap<>();
                    Iterator<String> keys = choice.keys();
                    while (keys.hasNext()) {
                        String name = keys.next();
                        values.put(name, choice.getString(name));
                    }
                    combinations.add(values);
                }
            }
            running = true;
            paused = true;
            stage = saved.optString("stage", "discover");
            publish("paused", "已恢复上次任务，回到原商品后可继续");
        } catch (Exception error) {
            publish("idle", "上次任务未能读取，已有采集记录仍可在结果中查看");
        }
    }

    private void recyclePreview() {
        if (preview != null) {
            preview.recycle();
            preview = null;
        }
    }

    private void recycleImages() {
        recyclePreview();
        if (thumbnail != null) {
            thumbnail.recycle();
            thumbnail = null;
        }
    }

    private static String readableError(Exception error) {
        String message = error.getMessage();
        return message == null || message.length() > 130 ? error.getClass().getSimpleName() : message;
    }

    private static void put(JSONObject object, String key, Object value) {
        try {
            object.put(key, value);
        } catch (Exception error) {
            throw new IllegalStateException("无法保存采集字段 " + key, error);
        }
    }
}
