package local.ai.collector;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.AtomicFile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** One durable phone-owned queue. Credentials stay in the browser, outside this file. */
public final class BatchStore {
    private static final Object LOCK = new Object();
    private static final ExecutorService MEDIA = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private BatchStore() { }

    private static AtomicFile file(Context context) {
        return new AtomicFile(new File(context.getFilesDir(), "kaogu_batch.json"));
    }

    private static JSONObject read(Context context) {
        try {
            return new JSONObject(new String(file(context).readFully(), StandardCharsets.UTF_8));
        } catch (Exception absent) {
            if (file(context).getBaseFile().exists() || new File(file(context).getBaseFile().getPath() + ".bak").exists()) {
                throw new IllegalStateException("批次进度未能读取，请保留应用数据", absent);
            }
            return new JSONObject();
        }
    }

    private static void write(Context context, JSONObject value) {
        AtomicFile target = file(context);
        FileOutputStream output = null;
        try {
            value.put("updated_at", Instant.now().toString());
            output = target.startWrite();
            output.write(value.toString().getBytes(StandardCharsets.UTF_8));
            target.finishWrite(output);
        } catch (Exception error) {
            if (output != null) target.failWrite(output);
            throw new IllegalStateException("批次进度尚未保存，请保留当前应用", error);
        }
    }

    public static void create(Context context, JSONArray source, JSONObject settings) {
        synchronized (LOCK) {
            JSONObject previous = read(context);
            if ("running".equals(previous.optString("status")) || "paused".equals(previous.optString("status"))) {
                throw new IllegalStateException("请先结束当前批次，再更换商品名单");
            }
            if (previous.has("qr_receipt")) throw new IllegalStateException("上一批次的临时二维码尚未清理，请先继续清理");
            if (source == null || source.length() == 0 || source.length() > 200) throw new IllegalArgumentException("本次请选择 1 至 200 件商品");
            JSONArray items = new JSONArray();
            Set<String> ids = new HashSet<>();
            for (int i = 0; i < source.length(); i++) {
                JSONObject original = source.optJSONObject(i);
                if (original == null) continue;
                String id = original.optString("product_id");
                String key = original.optString("kaogujia_key");
                if (!id.matches("[0-9]{10,25}") || !key.matches("[A-Za-z0-9_-]{1,200}")
                        || original.optString("title").trim().isEmpty()) throw new IllegalArgumentException("商品标识或标题未能核对，第 " + (i + 1) + " 项未加入");
                if (!ids.add(id)) continue;
                JSONObject item = new JSONObject();
                for (String name : new String[]{"kaogujia_key", "product_id", "title", "shop_name", "url", "list_image_url", "detail_link", "main_image_url", "video_url"}) {
                    put(item, name, original.optString(name));
                }
                put(item, "order", items.length() + 1);
                put(item, "status", "pending");
                items.put(item);
            }
            if (items.length() == 0) throw new IllegalArgumentException("没有可核对的商品");
            JSONObject batch = new JSONObject();
            put(batch, "batch_id", UUID.randomUUID().toString());
            put(batch, "created_at", Instant.now().toString());
            put(batch, "status", "ready");
            put(batch, "phase", "名单已保存，点击开始采集");
            put(batch, "index", 0);
            put(batch, "items", items);
            JSONObject cleanSettings = new JSONObject();
            if (settings != null) for (String name : new String[]{"keyword", "period", "page", "limit", "sort_field", "sort", "request_mode"}) {
                if (settings.has(name)) put(cleanSettings, name, settings.opt(name));
            }
            put(batch, "settings", cleanSettings);
            write(context, batch);
        }
    }

    public static void beginApp(Context context, String dateRange, int limit, String category, String workbenchJobId) {
        synchronized (LOCK) {
            if (!("yesterday".equals(dateRange) || "7d".equals(dateRange) || "30d".equals(dateRange))) {
                throw new IllegalArgumentException("请选择昨日、7日或30日");
            }
            if (limit <= 0) throw new IllegalArgumentException("本次商品数必须大于零");
            JSONObject previous = read(context);
            if ("running".equals(previous.optString("status"))) throw new IllegalStateException("请先结束当前批次，再开始新的 App 采集");
            if (previous.has("qr_receipt")) throw new IllegalStateException("上一批次的临时二维码尚未清理，请先完成清理");
            if (previous.length() > 0) archive(context, previous);
            JSONObject batch = new JSONObject();
            put(batch, "batch_id", UUID.randomUUID().toString());
            if (!workbenchJobId.isEmpty()) put(batch, "workbench_job_id", workbenchJobId);
            put(batch, "created_at", Instant.now().toString());
            put(batch, "status", "running");
            put(batch, "phase", "正在读取考古加 App 当前筛选列表");
            put(batch, "preparing", true);
            put(batch, "index", 0);
            put(batch, "items", new JSONArray());
            JSONObject configuration = new JSONObject();
            put(configuration, "source_mode", "kaogujia_app");
            put(configuration, "date_range", dateRange);
            put(configuration, "category_path", category);
            put(configuration, "limit", limit);
            put(configuration, "capture_sku_images", false);
            put(configuration, "max_combinations", context.getSharedPreferences("collector_settings", Context.MODE_PRIVATE)
                    .getInt("max_combinations", 24));
            put(batch, "settings", configuration);
            write(context, batch);
        }
    }

    public static JSONObject settings(Context context) {
        synchronized (LOCK) {
            JSONObject settings = read(context).optJSONObject("settings");
            return settings == null ? new JSONObject() : settings;
        }
    }

    /** Returns the number of newly appended cards, excluding duplicates and the limit. */
    public static int appendAppCards(Context context, JSONArray source) {
        synchronized (LOCK) {
            JSONObject batch = appBatch(context, true);
            if (source == null) throw new IllegalArgumentException("商品列表不能为空");
            JSONArray items = batch.optJSONArray("items");
            int limit = batch.optJSONObject("settings").optInt("limit");
            if (items == null || limit <= 0) throw new IllegalStateException("App 采集列表或商品数配置未能读取");
            Set<String> keys = new HashSet<>();
            for (int index = 0; index < items.length(); index++) keys.add(items.optJSONObject(index).optString("source_item_key"));
            int appended = 0;
            for (int index = 0; index < source.length() && items.length() < limit; index++) {
                JSONObject original = source.optJSONObject(index);
                if (original == null) throw new IllegalArgumentException("第 " + (index + 1) + " 项不是可核对的商品卡片");
                String title = original.optString("title").trim();
                String key = original.optString("source_item_key").trim();
                if (title.isEmpty() || key.isEmpty()) throw new IllegalArgumentException("第 " + (index + 1) + " 项缺少商品标题或来源标识");
                if (keys.contains(key)) continue;
                Object card = original.opt("source_card");
                if (card != null && card != JSONObject.NULL && !(card instanceof JSONObject)) {
                    throw new IllegalArgumentException("第 " + (index + 1) + " 项的来源卡片信息格式不正确");
                }
                JSONObject item = new JSONObject();
                put(item, "title", title);
                put(item, "source_item_key", key);
                put(item, "source_card", card == null || card == JSONObject.NULL ? new JSONObject() : card);
                put(item, "shop_name", original.optString("shop_name", ""));
                put(item, "order", items.length() + 1);
                put(item, "status", "pending");
                items.put(item);
                keys.add(key);
                appended++;
            }
            if (appended > 0) write(context, batch);
            return appended;
        }
    }

    public static void finishAppList(Context context, String note) {
        synchronized (LOCK) {
            JSONObject batch = appBatch(context, true);
            JSONArray items = batch.optJSONArray("items");
            if (items == null || items.length() == 0) throw new IllegalStateException("尚未读取到可核对的商品，保留列表准备状态");
            put(batch, "preparing", false);
            put(batch, "phase", note == null || note.trim().isEmpty() ? "商品名单已保存，准备逐件采集" : note);
            write(context, batch);
        }
    }

    public static void resetAppList(Context context) {
        synchronized (LOCK) {
            JSONObject batch = appBatch(context, true);
            put(batch, "items", new JSONArray());
            put(batch, "index", 0);
            put(batch, "phase", "正在重新读取考古加 App 当前筛选列表");
            write(context, batch);
        }
    }

    public static void updateCurrent(Context context, JSONObject metadata) {
        synchronized (LOCK) {
            JSONObject batch = appBatch(context, false);
            JSONArray items = batch.optJSONArray("items");
            JSONObject item = items == null ? null : items.optJSONObject(batch.optInt("index"));
            if (item == null) throw new IllegalStateException("尚未定位到当前 App 来源商品");
            if (metadata == null) throw new IllegalArgumentException("商品详情信息不能为空");
            boolean changed = false;
            for (String name : new String[]{"source_detail", "shop_name", "source_title", "identity_basis"}) {
                if (metadata.has(name)) {
                    put(item, name, metadata.opt(name));
                    changed = true;
                }
            }
            if (changed) write(context, batch);
        }
    }

    private static JSONObject appBatch(Context context, boolean preparing) {
        JSONObject batch = read(context);
        JSONObject configuration = batch.optJSONObject("settings");
        if (configuration == null || !"kaogujia_app".equals(configuration.optString("source_mode"))) {
            throw new IllegalStateException("当前批次不是官方 App 来源");
        }
        if ("stopped".equals(batch.optString("status")) || "complete".equals(batch.optString("status"))) {
            throw new IllegalStateException("该 App 批次已结束，不能更改名单或详情");
        }
        if (preparing && !batch.optBoolean("preparing")) throw new IllegalStateException("商品名单已确定，不能重新读取或追加");
        return batch;
    }

    private static void archive(Context context, JSONObject previous) {
        File directory = new File(context.getFilesDir(), "batch_history");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("无法保存上一批次的历史记录");
        File destination;
        do { destination = new File(directory, UUID.randomUUID().toString() + ".json"); }
        while (destination.exists() || new File(destination.getPath() + ".bak").exists() || new File(destination.getPath() + ".new").exists());
        AtomicFile history = new AtomicFile(destination);
        FileOutputStream stream = null;
        try {
            stream = history.startWrite();
            stream.write(previous.toString().getBytes(StandardCharsets.UTF_8));
            history.finishWrite(stream);
        } catch (Exception error) {
            if (stream != null) history.failWrite(stream);
            throw new IllegalStateException("上一批次尚未归档，原批次已保留", error);
        }
    }

    public static JSONArray items(Context context) {
        synchronized (LOCK) {
            JSONArray items = read(context).optJSONArray("items");
            return items == null ? new JSONArray() : items;
        }
    }

    public static JSONObject summary(Context context) {
        synchronized (LOCK) {
            JSONObject batch = read(context);
            JSONArray items = batch.optJSONArray("items");
            int count = items == null ? 0 : items.length();
            int complete = 0, partial = 0, failed = 0, invalid = 0;
            for (int i = 0; i < count; i++) {
                JSONObject item = items.optJSONObject(i);
                String status = item.optString("status");
                if ("complete".equals(status)) complete++;
                if ("partial".equals(status)) partial++;
                if ("failed".equals(status)) failed++;
                if ("failed".equals(status) && "invalid".equals(item.optString("product_state"))) invalid++;
            }
            JSONObject result = new JSONObject();
            int index = batch.optInt("index");
            put(result, "batch_id", batch.optString("batch_id"));
            put(result, "workbench_job_id", batch.optString("workbench_job_id"));
            put(result, "status", batch.optString("status", "idle"));
            put(result, "phase", batch.optString("phase", "先登录考古加并获取商品名单"));
            put(result, "total", count);
            put(result, "completed", complete + partial);
            put(result, "complete", complete);
            put(result, "partial", partial);
            put(result, "failed", failed);
            put(result, "invalid", invalid);
            put(result, "index", index);
            put(result, "title", index < count ? items.optJSONObject(index).optString("title") : "");
            put(result, "stop_requested", batch.optBoolean("stop_requested"));
            JSONObject configuration = batch.optJSONObject("settings");
            put(result, "source_mode", configuration == null ? "" : configuration.optString("source_mode"));
            put(result, "preparing", batch.optBoolean("preparing"));
            put(result, "requested_count", configuration == null ? count : configuration.optInt("limit", count));
            return result;
        }
    }

    public static JSONObject current(Context context) {
        synchronized (LOCK) {
            JSONObject batch = read(context);
            JSONArray items = batch.optJSONArray("items");
            int index = batch.optInt("index");
            if (items == null || index >= items.length()) return null;
            JSONObject item = items.optJSONObject(index);
            put(item, "batch_id", batch.optString("batch_id"));
            if (batch.has("workbench_job_id")) put(item, "workbench_job_id", batch.optString("workbench_job_id"));
            put(item, "batch_index", index);
            return item;
        }
    }

    public static void activate(Context context) {
        synchronized (LOCK) {
            JSONObject batch = read(context);
            JSONObject configuration = batch.optJSONObject("settings");
            boolean preparingApp = batch.optBoolean("preparing") && configuration != null
                    && "kaogujia_app".equals(configuration.optString("source_mode"));
            if (batch.optString("batch_id").isEmpty() || current(context) == null && !preparingApp) throw new IllegalStateException("请先获取商品名单");
            if ("stopped".equals(batch.optString("status"))) throw new IllegalStateException("该批次已结束，请重新获取名单");
            put(batch, "status", "running");
            put(batch, "phase", "正在继续当前商品");
            JSONObject item = batch.optJSONArray("items").optJSONObject(batch.optInt("index"));
            if (item != null) {
                item.remove("qr_request_id");
                if ("fetching_qr".equals(item.optString("status"))) put(item, "status", "pending");
            }
            write(context, batch);
        }
    }

    public static void phase(Context context, String phase, String itemStatus) {
        synchronized (LOCK) {
            JSONObject batch = read(context);
            if (batch.optString("batch_id").isEmpty()) return;
            JSONArray items = batch.optJSONArray("items");
            JSONObject item = items == null ? null : items.optJSONObject(batch.optInt("index"));
            if (phase.equals(batch.optString("phase")) && (item == null || itemStatus == null || itemStatus.equals(item.optString("status")))) return;
            put(batch, "phase", phase);
            if (item != null && itemStatus != null) put(item, "status", itemStatus);
            write(context, batch);
        }
    }

    public static void pause(Context context, String message) {
        synchronized (LOCK) {
            JSONObject batch = read(context);
            if (batch.optString("batch_id").isEmpty()) return;
            put(batch, "status", "paused");
            put(batch, "phase", message);
            JSONArray items = batch.optJSONArray("items");
            JSONObject item = items == null ? null : items.optJSONObject(batch.optInt("index"));
            if (item != null) item.remove("qr_request_id");
            write(context, batch);
        }
    }

    public static void stop(Context context, String message) {
        synchronized (LOCK) {
            JSONObject batch = read(context);
            if (batch.optString("batch_id").isEmpty()) return;
            put(batch, "status", "stopped");
            put(batch, "stop_requested", true);
            put(batch, "phase", message);
            write(context, batch);
        }
    }

    public static JSONObject qrReceipt(Context context) {
        synchronized (LOCK) { return read(context).optJSONObject("qr_receipt"); }
    }

    public static void clearQr(Context context, JSONObject expected) {
        synchronized (LOCK) {
            JSONObject batch = read(context);
            JSONObject actual = batch.optJSONObject("qr_receipt");
            if (actual == null) return;
            if (expected == null || !actual.optString("uri").equals(expected.optString("uri"))
                    || !actual.optString("qr_request_id").equals(expected.optString("qr_request_id"))) {
                throw new IllegalStateException("二维码清理凭据已变化，保留当前批次");
            }
            batch.remove("qr_receipt");
            write(context, batch);
        }
    }

    public static JSONObject requestQr(Context context) {
        synchronized (LOCK) {
            JSONObject batch = read(context);
            if (!"running".equals(batch.optString("status"))) throw new IllegalStateException("批次尚未开始");
            JSONObject item = batch.optJSONArray("items").optJSONObject(batch.optInt("index"));
            put(item, "qr_request_id", UUID.randomUUID().toString());
            put(item, "status", "fetching_qr");
            put(batch, "phase", "正在从考古加获取本件商品二维码");
            write(context, batch);
            return current(context);
        }
    }

    /** The browser passes its original request item, including the batch identity. */
    public static void acceptQr(Context context, JSONObject requested, byte[] png) {
        Context app = context.getApplicationContext();
        MEDIA.execute(() -> {
            JSONObject receipt = null;
            try {
                synchronized (LOCK) {
                    if (!matches(app, requested)) return;
                    if (qrReceipt(app) != null) throw new IllegalStateException("上一张临时二维码尚未清理");
                }
                receipt = QrAlbum.save(app, requested, png);
                put(receipt, "qr_request_id", requested.optString("qr_request_id"));
                synchronized (LOCK) {
                    if (!matches(app, requested)) {
                        QrAlbum.remove(app, receipt);
                        return;
                    }
                    JSONObject batch = read(app);
                    put(batch, "qr_receipt", receipt);
                    put(batch, "phase", "二维码已保存，正在打开抖音商城扫码");
                    JSONObject item = batch.optJSONArray("items").optJSONObject(batch.optInt("index"));
                    for (String name : new String[]{"main_image_url", "main_image", "video_url", "video_status", "detail_field_names", "qr_source"}) {
                        if (requested.has(name)) put(item, name, requested.opt(name));
                    }
                    put(item, "status", "qr_ready");
                    write(app, batch);
                }
                MAIN.post(() -> CollectorService.qrReady(app));
            } catch (Exception error) {
                try {
                    if (receipt != null && qrReceipt(app) == null) QrAlbum.remove(app, receipt);
                } catch (Exception cleanupPending) {
                    // QrAlbum's durable own-image journal is recovered before the next request.
                }
                try { browserFailure(app, requested, message(error)); }
                catch (Exception persistenceError) {
                    MAIN.post(() -> CollectorService.batchBrowserFailure(app, "批次进度暂时无法保存，已停止等待核对"));
                }
            }
        });
    }

    private static boolean matches(Context context, JSONObject requested) {
        JSONObject batch = read(context);
        JSONObject item = current(context);
        return requested != null && item != null && "running".equals(batch.optString("status"))
                && requested.optString("batch_id").equals(batch.optString("batch_id"))
                && requested.optString("product_id").equals(item.optString("product_id"))
                && !requested.optString("qr_request_id").isEmpty()
                && requested.optString("qr_request_id").equals(item.optString("qr_request_id"))
                && "fetching_qr".equals(item.optString("status"));
    }

    public static void browserFailure(Context context, String message) {
        context.getSharedPreferences("kaogu_browser", Context.MODE_PRIVATE).edit().putString("error", message).apply();
    }

    public static void browserProgress(Context context, JSONObject requested, String message) {
        synchronized (LOCK) {
            if (!matches(context, requested)) return;
            phase(context, message, "fetching_qr");
        }
    }

    public static void browserFailure(Context context, JSONObject requested, String message) {
        browserFailure(context, message);
        synchronized (LOCK) {
            if (!matches(context, requested)) return;
            pause(context, message);
            MAIN.post(() -> CollectorService.batchBrowserFailure(context, message));
        }
    }

    public static void finishItem(Context context, String status, String recordId, String error) {
        synchronized (LOCK) {
            JSONObject batch = read(context);
            JSONArray items = batch.optJSONArray("items");
            JSONObject item = items == null ? null : items.optJSONObject(batch.optInt("index"));
            if (item == null) throw new IllegalStateException("没有当前商品");
            put(item, "status", status);
            if (recordId != null) put(item, "record_id", recordId);
            if (error != null && !error.isEmpty()) put(item, "error", error);
            else item.remove("error");
            String invalid = invalidReason(error);
            if ("failed".equals(status) && !invalid.isEmpty()) {
                put(item, "product_state", "invalid");
                put(item, "invalid_reason", invalid);
            } else {
                item.remove("product_state");
                item.remove("invalid_reason");
            }
            put(item, "finished_at", Instant.now().toString());
            write(context, batch);
        }
    }

    public static boolean advance(Context context) {
        synchronized (LOCK) {
            JSONObject batch = read(context);
            if (batch.has("qr_receipt")) throw new IllegalStateException("本件二维码未确认删除，批次已保留");
            JSONArray items = batch.optJSONArray("items");
            int index = batch.optInt("index");
            JSONObject item = items == null ? null : items.optJSONObject(index);
            if (item == null || !("complete".equals(item.optString("status")) || "partial".equals(item.optString("status")) || "failed".equals(item.optString("status")))) {
                throw new IllegalStateException("当前商品尚未保存，不能跳到下一件");
            }
            index++;
            put(batch, "index", index);
            boolean more = items != null && index < items.length();
            put(batch, "status", more ? "running" : "complete");
            put(batch, "phase", more ? "正在获取下一件商品二维码" : "本批次已结束，采集记录已保存在手机");
            write(context, batch);
            return more;
        }
    }

    public static String invalidReason(String error) {
        if (error == null) return "";
        if (error.contains("商品已下架")) return "商品已下架";
        if (error.contains("商品已失效")) return "商品已失效";
        if (error.contains("商品不存在")) return "商品不存在";
        return "";
    }

    public static int retryFailed(Context context) {
        synchronized (LOCK) {
            JSONObject batch = read(context);
            if (!"complete".equals(batch.optString("status"))) throw new IllegalStateException("请先让当前批次完成，再重试失败商品");
            if (batch.has("workbench_job_id")) throw new IllegalStateException("请在工作台新建采集任务");
            JSONArray items = batch.optJSONArray("items");
            int count = 0, first = -1;
            for (int i = 0; items != null && i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null || !"failed".equals(item.optString("status"))) continue;
                if (first < 0) first = i;
                put(item, "status", "pending");
                put(item, "last_error", item.optString("error"));
                item.remove("error");
                item.remove("record_id");
                count++;
            }
            if (count == 0) throw new IllegalStateException("本批次没有失败商品");
            put(batch, "index", first);
            put(batch, "status", "paused");
            put(batch, "stop_requested", false);
            put(batch, "phase", "准备重试 " + count + " 件失败商品，已完成商品将跳过");
            write(context, batch);
            return count;
        }
    }

    private static String message(Exception error) {
        String value = error.getMessage();
        return value == null || value.length() > 180 ? "二维码未能准备完成，请保留当前批次后重试" : value;
    }

    private static void put(JSONObject value, String name, Object data) {
        try { value.put(name, data); }
        catch (Exception error) { throw new IllegalStateException("无法保存批次字段", error); }
    }
}
