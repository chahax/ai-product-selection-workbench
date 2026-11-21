package local.ai.collector;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.AtomicFile;
import android.util.Base64;
import android.util.Base64OutputStream;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Durable records and acknowledged uploads. Metadata remains after media is uploaded. */
public final class RecordStore {
    private static final Object LOCK = new Object();
    private static final ExecutorService UPLOAD_EXECUTOR = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean UPLOADING = new AtomicBoolean(false);
    private static final AtomicBoolean UPLOAD_QUEUED = new AtomicBoolean(false);
    private static final AtomicBoolean UPLOAD_REQUESTED = new AtomicBoolean(false);
    private static final int RESPONSE_LIMIT = 1024 * 1024;

    private RecordStore() { }

    public static final class UploadSummary {
        public int uploaded;
        public int pending;
        public int failed;
        public boolean configured;
        public boolean busy;
        public String message = "";
    }

    public static final class DeleteSummary {
        public int deleted;
        public int active;
        public int failed;
    }

    private static final class ActiveRecordException extends IllegalStateException {
        ActiveRecordException(String message) { super(message); }
    }

    public static DeleteSummary deleteSavedRecords(Context context, List<String> recordIds) {
        if (!UPLOADING.compareAndSet(false, true)) throw new IllegalStateException("正在回传，请等回传结束后再清空");
        try {
            DeleteSummary result = new DeleteSummary();
            synchronized (LOCK) {
                for (String id : new LinkedHashSet<>(recordIds)) {
                    try { if (delete(context, id)) result.deleted++; }
                    catch (ActiveRecordException error) { result.active++; }
                    catch (RuntimeException error) { result.failed++; }
                }
            }
            return result;
        } finally {
            UPLOADING.set(false);
        }
    }

    public static String deviceId(Context context) {
        SharedPreferences preferences = context.getSharedPreferences("collector_device", Context.MODE_PRIVATE);
        synchronized (LOCK) {
            String id = preferences.getString("device_id", "");
            if (id.isEmpty()) {
                id = UUID.randomUUID().toString();
                if (!preferences.edit().putString("device_id", id).commit()) {
                    throw new IllegalStateException("无法保存手机标识");
                }
            }
            return id;
        }
    }

    public static String save(Context context, JSONObject source) {
        Context app = context.getApplicationContext();
        String id;
        synchronized (LOCK) {
            try {
                JSONObject record = new JSONObject(source.toString());
                id = record.optString("record_id", "");
                if (!id.isEmpty() && !id.matches("[A-Za-z0-9._-]{1,120}")) {
                    throw new IllegalArgumentException("采集记录编号格式不正确");
                }
                if (id.isEmpty()) {
                    String task = record.optString("task_id", "");
                    String identity = task.isEmpty() ? UUID.randomUUID().toString() : task;
                    identity += "|" + record.optString("platform", "douyin_mall") + "|"
                            + record.optString("product_id", record.optString("title", "当前商品"));
                    id = "record-" + sha256(identity.getBytes(StandardCharsets.UTF_8)).substring(0, 32);
                }
                JSONObject previous = read(recordFile(app, id));
                int revision = previous == null ? 1 : previous.optInt("revision", 0) + 1;
                record.put("record_id", id);
                record.put("revision", revision);
                record.put("device_id", deviceId(app));
                record.put("saved_at", now());
                record.put("uploaded", false);
                record.put("upload_attempt_count", previous == null ? 0 : previous.optInt("upload_attempt_count", 0));
                record.remove("uploaded_at");
                record.remove("upload_error");
                write(recordFile(app, id), record);
                changed(app);
            } catch (IOException | JSONException exception) {
                throw new IllegalStateException("采集结果未能保存，请检查手机存储空间", exception);
            }
        }
        scheduleUpload(app);
        return id;
    }

    public static JSONArray records(Context context) {
        synchronized (LOCK) {
            JSONArray result = new JSONArray();
            File[] files = directory(context).listFiles((folder, name) -> name.endsWith(".json") || name.endsWith(".json.bak"));
            if (files == null) return result;
            Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
            Set<String> seen = new HashSet<>();
            for (File file : files) {
                File base = file.getName().endsWith(".bak")
                        ? new File(file.getParentFile(), file.getName().substring(0, file.getName().length() - 4)) : file;
                if (!seen.add(base.getAbsolutePath())) continue;
                JSONObject record = read(base);
                if (record != null) result.put(record);
            }
            return result;
        }
    }

    /** Deletes one saved local record; an active task and shared media are retained. */
    public static boolean delete(Context context, String recordId) {
        if (recordId == null || !recordId.matches("[A-Za-z0-9._-]{1,120}")) {
            throw new IllegalArgumentException("采集记录编号格式不正确");
        }
        synchronized (LOCK) {
            File target = recordFile(context, recordId);
            JSONObject record = read(target);
            if (record == null) {
                if (target.exists() || new File(target.getPath() + ".bak").exists()) {
                    throw new IllegalStateException("记录内容暂时无法读取，未删除任何内容");
                }
                return false;
            }
            if (!recordId.equals(record.optString("record_id"))) throw new IllegalStateException("记录编号未能核对，未删除任何内容");
            String workbenchJobId = record.optString("workbench_job_id");
            if (!workbenchJobId.isEmpty() && (!record.optBoolean("uploaded")
                    || workbenchJobId.equals(WorkbenchClient.get(context).jobId()))) {
                throw new ActiveRecordException("这条工作台记录仍在任务中或等待入库，请稍后删除");
            }
            JSONObject checkpoint = taskFile(context, "active_task.json");
            JSONObject batch = taskFile(context, "kaogu_batch.json");
            if (checkpoint.length() > 0 && checkpoint.optJSONObject("record") == null) {
                throw new IllegalStateException("活动任务内容暂时无法核对，未删除任何内容");
            }
            if (sameRecord(record, checkpoint.optJSONObject("record"))) {
                throw new ActiveRecordException("这件商品仍有未结束的采集任务，请先结束任务再删除");
            }
            Map<String, ?> liveState = context.getSharedPreferences("collector_state", Context.MODE_PRIVATE).getAll();
            Object task = liveState.get("task_id");
            Object state = liveState.get("status");
            if (!record.optString("task_id").isEmpty() && record.optString("task_id").equals(task)
                    && ("running".equals(state) || "collecting".equals(state) || "paused".equals(state) || "waiting".equals(state))) {
                throw new ActiveRecordException("这件商品仍是当前采集任务，暂不能删除");
            }
            String batchStatus = batch.optString("status");
            JSONArray items = batch.optJSONArray("items");
            if (("running".equals(batchStatus) || "paused".equals(batchStatus)) && items == null) {
                throw new IllegalStateException("当前批次内容暂时无法核对，未删除任何内容");
            }
            JSONObject current = items == null ? null : items.optJSONObject(batch.optInt("index"));
            if (current != null && ("running".equals(batchStatus) || "paused".equals(batchStatus))) {
                JSONObject active;
                try {
                    active = new JSONObject(current.toString());
                    active.put("batch_id", batch.optString("batch_id"));
                }
                catch (JSONException impossible) { throw new IllegalStateException("当前任务身份未能核对", impossible); }
                if (sameRecord(record, active)) throw new ActiveRecordException("这件商品仍在当前批次处理中，请处理完再删除");
            }
            Set<String> protectedPaths = deletionProtectedImages(context, record, checkpoint, batch);
            AtomicFile saved = new AtomicFile(target);
            saved.delete();
            if (target.exists() || new File(target.getPath() + ".bak").exists() || new File(target.getPath() + ".new").exists()) {
                throw new IllegalStateException("记录文件尚未确认删除，请稍后重试");
            }
            changed(context);
            if (protectedPaths != null) for (ImageFile image : imageFiles(context, record)) {
                try {
                    if (!protectedPaths.contains(image.file.getCanonicalPath()) && image.file.isFile()) {
                        imageMimeType(image.file);
                        image.file.delete();
                    }
                } catch (IOException | SecurityException ignored) {
                    // The record is deleted; retain any media that could not be safely cleaned.
                }
            }
            return true;
        }
    }

    private static JSONObject taskFile(Context context, String name) {
        File source = new File(context.getFilesDir(), name);
        JSONObject value = read(source);
        if (value == null && (source.exists() || new File(source.getPath() + ".bak").exists())) {
            throw new IllegalStateException("当前任务状态暂时无法核对，未删除任何内容");
        }
        return value == null ? new JSONObject() : value;
    }

    private static boolean sameRecord(JSONObject saved, JSONObject active) {
        if (active == null) return false;
        if (!saved.optString("record_id").isEmpty() && saved.optString("record_id").equals(active.optString("record_id"))) return true;
        if (!saved.optString("task_id").isEmpty() && saved.optString("task_id").equals(active.optString("task_id"))) return true;
        if (saved.optString("batch_id").isEmpty() || !saved.optString("batch_id").equals(active.optString("batch_id"))) return false;
        String source = saved.optString("source_item_key");
        if (!source.isEmpty()) return source.equals(active.optString("source_item_key"));
        String product = saved.optString("product_id");
        return !product.isEmpty() && product.equals(active.optString("product_id"));
    }

    private static Set<String> deletionProtectedImages(Context context, JSONObject deleting, JSONObject checkpoint, JSONObject batch) {
        try {
            String excluding = deleting.optString("record_id");
            Set<String> protectedPaths = new HashSet<>();
            Set<String> seen = new HashSet<>();
            File[] files = directory(context).listFiles((parent, name) -> name.endsWith(".json") || name.endsWith(".json.bak"));
            if (files == null) return null;
            for (File file : files) {
                File base = file.getName().endsWith(".bak") ? new File(file.getParentFile(), file.getName().substring(0, file.getName().length() - 4)) : file;
                if (!seen.add(base.getAbsolutePath()) || base.equals(recordFile(context, excluding))) continue;
                JSONObject other = read(base);
                if (other == null) return null;
                for (ImageFile image : imageFiles(context, other)) protectedPaths.add(image.file.getCanonicalPath());
            }
            JSONObject active = checkpoint.optJSONObject("record");
            if (active != null) for (ImageFile image : imageFiles(context, active)) protectedPaths.add(image.file.getCanonicalPath());
            JSONArray items = batch.optJSONArray("items");
            if (items != null) for (int index = 0; index < items.length(); index++) {
                JSONObject item = items.optJSONObject(index);
                if (item != null && excluding.equals(item.optString("record_id"))) continue;
                if (item != null && !deleting.optString("batch_id").isEmpty()
                        && deleting.optString("batch_id").equals(batch.optString("batch_id"))) {
                    String source = deleting.optString("source_item_key"), product = deleting.optString("product_id");
                    if (!source.isEmpty() && source.equals(item.optString("source_item_key"))
                            || source.isEmpty() && !product.isEmpty() && product.equals(item.optString("product_id"))) continue;
                }
                if (item != null) for (ImageFile image : imageFiles(context, item)) protectedPaths.add(image.file.getCanonicalPath());
            }
            return protectedPaths;
        } catch (IOException | RuntimeException error) {
            return null;
        }
    }

    public static int pendingCount(Context context) {
        JSONArray records = records(context);
        int count = 0;
        for (int index = 0; index < records.length(); index++) {
            if (!records.optJSONObject(index).optBoolean("uploaded", false)) count++;
        }
        return count;
    }

    static final class JobUploads {
        int total;
        int pending;
    }

    static JobUploads workbenchUploads(Context context, String jobId) {
        JobUploads count = new JobUploads();
        JSONArray saved = records(context);
        for (int index = 0; index < saved.length(); index++) {
            JSONObject record = saved.optJSONObject(index);
            if (!jobId.equals(record.optString("workbench_job_id"))) continue;
            count.total++;
            if (!record.optBoolean("uploaded")) count.pending++;
        }
        return count;
    }

    static boolean hasPendingWorkbenchUploads(Context context) {
        JSONArray saved = records(context);
        for (int index = 0; index < saved.length(); index++) {
            JSONObject record = saved.optJSONObject(index);
            if (!record.optString("workbench_job_id").isEmpty() && !record.optBoolean("uploaded")) return true;
        }
        return false;
    }

    public static long pendingBytes(Context context) {
        long bytes = 0;
        Set<String> seen = new HashSet<>();
        JSONArray records = records(context);
        for (int index = 0; index < records.length(); index++) {
            JSONObject record = records.optJSONObject(index);
            if (record.optBoolean("uploaded", false)) continue;
            bytes += record.toString().getBytes(StandardCharsets.UTF_8).length;
            for (ImageFile image : imageFiles(context, record)) {
                if (seen.add(image.file.getAbsolutePath())) bytes += image.file.length();
            }
        }
        return bytes;
    }

    public static void scheduleUpload(Context context) {
        Context app = context.getApplicationContext();
        if (settings(app).getString("server_url", "").trim().isEmpty() && !WorkbenchClient.get(app).paired()) return;
        UPLOAD_REQUESTED.set(true);
        if (!UPLOAD_QUEUED.compareAndSet(false, true)) return;
        UPLOAD_EXECUTOR.execute(() -> {
            try {
                do {
                    UPLOAD_REQUESTED.set(false);
                    uploadPending(app);
                } while (UPLOAD_REQUESTED.get());
            } finally {
                UPLOAD_QUEUED.set(false);
                if (UPLOAD_REQUESTED.get()) scheduleUpload(app);
            }
        });
    }

    /** Call on a worker thread. A failed attempt leaves every unacknowledged file intact. */
    public static UploadSummary uploadPending(Context context) {
        Context app = context.getApplicationContext();
        UploadSummary summary = new UploadSummary();
        Map<String, ?> configuration = settings(app).getAll();
        Object address = configuration.get("server_url");
        Object secret = configuration.get("auth_token");
        String endpoint = address instanceof String ? ((String) address).trim() : "";
        String token = secret instanceof String ? ((String) secret).trim() : "";
        WorkbenchConnection workbench = WorkbenchClient.get(app).connection();
        summary.configured = !endpoint.isEmpty() || workbench != null;
        try {
            summary.pending = pendingCount(app);
        } catch (IllegalStateException exception) {
            summary.failed = 1;
            summary.message = friendlyError(exception);
            return summary;
        }
        if (!summary.configured) {
            summary.message = "待配置回传地址";
            return summary;
        }
        if (!UPLOADING.compareAndSet(false, true)) {
            summary.busy = true;
            summary.message = "正在上传，结果会自动更新";
            return summary;
        }
        try {
            JSONArray snapshot = records(app);
            boolean legacyBlocked = false, workbenchBlocked = false;
            for (int index = snapshot.length() - 1; index >= 0; index--) {
                JSONObject record = snapshot.optJSONObject(index);
                if (record.optBoolean("uploaded", false)) continue;
                String jobId = record.optString("workbench_job_id");
                boolean managed = !jobId.isEmpty();
                if (managed ? workbench == null || workbenchBlocked : endpoint.isEmpty() || legacyBlocked) continue;
                synchronized (LOCK) {
                    JSONObject current = read(recordFile(app, record.optString("record_id")));
                    if (current == null || current.optBoolean("uploaded", false)
                            || current.optInt("revision") != record.optInt("revision")) continue;
                }
                try {
                    HttpURLConnection connection = managed ? workbench.open("/api/mobile/records") : openLegacy(endpoint, token);
                    post(app, connection, record, jobId);
                    if (acknowledge(app, record)) summary.uploaded++;
                } catch (IOException | JSONException exception) {
                    String reason = friendlyError(exception);
                    markFailure(app, record, reason);
                    summary.failed++;
                    summary.message = reason + "，记录和图片已保留";
                    if (managed) workbenchBlocked = true;
                    else legacyBlocked = true;
                }
            }
            summary.pending = pendingCount(app);
            if (summary.failed == 0) {
                summary.message = summary.pending == 0 ? "服务器已确认接收全部记录" : "还有记录等待上传";
            }
        } catch (IllegalStateException exception) {
            summary.failed++;
            summary.message = friendlyError(exception);
        } finally {
            try {
                app.getSharedPreferences("collector_upload", Context.MODE_PRIVATE).edit()
                        .putString("last_message", summary.message).putString("last_attempt_at", now()).apply();
            } finally {
                UPLOADING.set(false);
            }
        }
        return summary;
    }

    private static HttpURLConnection openLegacy(String endpoint, String token) throws IOException {
        URL target = new URL(endpoint);
        if (!target.getProtocol().equals("https") && !target.getProtocol().equals("http")) {
            throw new IOException("回传地址需要以 https:// 或 http:// 开头");
        }
        HttpURLConnection connection = (HttpURLConnection) target.openConnection();
        connection.setInstanceFollowRedirects(false);
        if (!token.isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + token);
        return connection;
    }

    private static void post(Context app, HttpURLConnection connection, JSONObject record, String jobId) throws IOException, JSONException {
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(20000);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setChunkedStreamingMode(65536);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setRequestProperty("Accept", "application/json");
        String id = record.getString("record_id");
        connection.setRequestProperty("Idempotency-Key", id + ":" + record.optInt("revision", 1));
        try {
            validateImagePaths(app, record);
            List<ImageFile> images = imageFiles(app, record);
            for (ImageFile image : images) {
                if (!image.file.isFile()) throw new IOException("有采集图片缺失，已暂停本条上传");
                image.mimeType = imageMimeType(image.file);
            }
            try (OutputStream stream = connection.getOutputStream()) {
                text(stream, "{\"schema_version\":1,\"device_id\":" + JSONObject.quote(deviceId(app))
                        + ",\"device_model\":" + JSONObject.quote(Build.MANUFACTURER + " " + Build.MODEL)
                        + ",\"record_id\":" + JSONObject.quote(id)
                        + ",\"revision\":" + record.optInt("revision", 1)
                        + (jobId.isEmpty() ? "" : ",\"job_id\":" + JSONObject.quote(jobId))
                        + ",\"record\":" + record.toString() + ",\"images\":[");
                for (int index = 0; index < images.size(); index++) {
                    ImageFile image = images.get(index);
                    if (index > 0) text(stream, ",");
                    text(stream, "{\"kind\":" + JSONObject.quote(image.kind)
                            + ",\"spec_index\":" + image.specIndex + ",\"image_index\":" + image.imageIndex
                            + ",\"file_name\":" + JSONObject.quote(image.file.getName())
                            + ",\"mime_type\":" + JSONObject.quote(image.mimeType) + ",\"sha256\":" + JSONObject.quote(fileHash(image.file))
                            + ",\"data_base64\":\"");
                    Base64OutputStream encoded = new Base64OutputStream(stream, Base64.NO_WRAP | Base64.NO_CLOSE);
                    try (InputStream input = new FileInputStream(image.file)) {
                        byte[] buffer = new byte[65536];
                        int length;
                        while ((length = input.read(buffer)) != -1) encoded.write(buffer, 0, length);
                    }
                    encoded.close();
                    text(stream, "\"}");
                }
                text(stream, "]}");
            }
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw new IOException("服务器返回 HTTP " + status);
            JSONObject reply;
            try (InputStream stream = connection.getInputStream()) {
                reply = new JSONObject(readText(stream, RESPONSE_LIMIT));
            }
            if (!Boolean.TRUE.equals(reply.opt("acknowledged")) || !id.equals(reply.opt("record_id"))) {
                throw new IOException("服务器没有确认这条记录的接收编号");
            }
            Object confirmedRevision = reply.opt("revision");
            if (!(confirmedRevision instanceof Number)
                    || ((Number) confirmedRevision).doubleValue() != record.optInt("revision", 1)) {
                throw new IOException("服务器确认的记录版本不一致");
            }
        } finally {
            connection.disconnect();
        }
    }

    private static boolean acknowledge(Context context, JSONObject sent) throws IOException, JSONException {
        synchronized (LOCK) {
            File destination = recordFile(context, sent.getString("record_id"));
            JSONObject current = read(destination);
            if (current == null || current.optInt("revision") != sent.optInt("revision")) return false;
            current.put("uploaded", true);
            current.put("uploaded_at", now());
            current.remove("upload_error");
            write(destination, current);
            changed(context);
            boolean cleaned = cleanAcknowledgedImages(context, sent);
            try {
                current.put("image_cleanup_pending", !cleaned);
                write(destination, current);
            } catch (IOException ignored) {
                // The acknowledgement is already durable. Cleanup notes must not reverse it.
            }
            return true;
        }
    }

    private static boolean cleanAcknowledgedImages(Context context, JSONObject sent) {
        try {
            Set<String> protectedPaths = new HashSet<>();
            JSONArray all = records(context);
            for (int index = 0; index < all.length(); index++) {
                JSONObject other = all.optJSONObject(index);
                if (other.optBoolean("uploaded", false)) continue;
                for (ImageFile image : imageFiles(context, other)) protectedPaths.add(image.file.getCanonicalPath());
            }
            boolean cleaned = true;
            for (ImageFile image : imageFiles(context, sent)) {
                if (!protectedPaths.contains(image.file.getCanonicalPath()) && image.file.isFile() && !image.file.delete()) cleaned = false;
            }
            return cleaned;
        } catch (IOException | RuntimeException ignored) {
            return false;
        }
    }

    private static void markFailure(Context context, JSONObject sent, String reason) {
        synchronized (LOCK) {
            try {
                File file = recordFile(context, sent.getString("record_id"));
                JSONObject current = read(file);
                if (current == null || current.optBoolean("uploaded", false)
                        || current.optInt("revision") != sent.optInt("revision")) return;
                current.put("upload_error", reason);
                current.put("last_upload_attempt_at", now());
                current.put("upload_attempt_count", current.optInt("upload_attempt_count", 0) + 1);
                write(file, current);
                changed(context);
            } catch (IOException | JSONException ignored) {
                // The original pending record is still present when an error note cannot be written.
            }
        }
    }

    private static List<ImageFile> imageFiles(Context context, JSONObject record) {
        List<ImageFile> images = new ArrayList<>();
        JSONObject mainImage = record.optJSONObject("main_image");
        if (mainImage != null) addImage(context, images, mainImage, "main_image", -1, 0);
        JSONArray covers = record.optJSONArray("product_images");
        for (int i = 0; covers != null && i < covers.length(); i++) {
            JSONObject image = covers.optJSONObject(i);
            if (image != null && (mainImage == null || !image.optString("file").equals(mainImage.optString("file")))) {
                addImage(context, images, image, "product_image", -1, Math.max(0, image.optInt("position", i + 1) - 1));
            }
        }
        JSONArray skus = record.optJSONArray("skus");
        if (skus == null) return images;
        for (int spec = 0; spec < skus.length(); spec++) {
            JSONObject sku = skus.optJSONObject(spec);
            if (sku == null) continue;
            JSONObject image = sku.optJSONObject("image");
            if (image != null) addImage(context, images, image, "sku_image", spec, 0);
            JSONArray multiple = sku.optJSONArray("images");
            if (multiple != null) {
                for (int index = 0; index < multiple.length(); index++) {
                    if (multiple.optJSONObject(index) != null) addImage(context, images, multiple.optJSONObject(index), "sku_image", spec, index);
                }
            }
        }
        return images;
    }

    private static void validateImagePaths(Context context, JSONObject record) throws IOException {
        validateImagePath(context, record.optJSONObject("main_image"));
        JSONArray covers = record.optJSONArray("product_images");
        for (int i = 0; covers != null && i < covers.length(); i++) validateImagePath(context, covers.optJSONObject(i));
        JSONArray skus = record.optJSONArray("skus");
        if (skus == null) return;
        for (int index = 0; index < skus.length(); index++) {
            JSONObject sku = skus.optJSONObject(index);
            if (sku == null) continue;
            validateImagePath(context, sku.optJSONObject("image"));
            JSONArray images = sku.optJSONArray("images");
            if (images != null) for (int item = 0; item < images.length(); item++) {
                validateImagePath(context, images.optJSONObject(item));
            }
        }
    }

    private static void validateImagePath(Context context, JSONObject image) throws IOException {
        if (image == null || image.optString("file", "").isEmpty()) return;
        String path = new File(image.optString("file")).getCanonicalPath();
        String root = context.getFilesDir().getCanonicalPath() + File.separator;
        String records = directory(context).getCanonicalPath() + File.separator;
        if (!path.startsWith(root) || path.startsWith(records)) {
            throw new IOException("采集图片路径不属于本次采集缓存，已暂停本条上传");
        }
    }

    private static void addImage(Context context, List<ImageFile> images, JSONObject image, String kind, int spec, int index) {
        String path = image.optString("file", "");
        if (path.isEmpty()) return;
        File file = new File(path);
        try {
            String parent = context.getFilesDir().getCanonicalPath() + File.separator;
            if (file.getCanonicalPath().startsWith(parent) && !file.getCanonicalPath().startsWith(directory(context).getCanonicalPath() + File.separator)) {
                images.add(new ImageFile(file, kind, spec, index));
            }
        } catch (IOException ignored) { }
    }

    private static final class ImageFile {
        final File file;
        final String kind;
        final int specIndex;
        final int imageIndex;
        String mimeType;
        ImageFile(File file, String kind, int specIndex, int imageIndex) {
            this.file = file;
            this.kind = kind;
            this.specIndex = specIndex;
            this.imageIndex = imageIndex;
        }
    }

    private static String imageMimeType(File file) throws IOException {
        byte[] header = new byte[64];
        int length = 0;
        try (InputStream stream = new FileInputStream(file)) {
            int count;
            while (length < header.length && (count = stream.read(header, length, header.length - length)) > 0) length += count;
        }
        if (length >= 8 && (header[0] & 255) == 137 && header[1] == 80 && header[2] == 78 && header[3] == 71
                && header[4] == 13 && header[5] == 10 && header[6] == 26 && header[7] == 10) return "image/png";
        if (length >= 3 && (header[0] & 255) == 255 && (header[1] & 255) == 216 && (header[2] & 255) == 255) return "image/jpeg";
        if (length >= 6 && ascii(header, 0, "GIF8") && (header[4] == 55 || header[4] == 57) && header[5] == 97) return "image/gif";
        if (length >= 12 && ascii(header, 0, "RIFF") && ascii(header, 8, "WEBP")) return "image/webp";
        if (length >= 2 && header[0] == 66 && header[1] == 77) return "image/bmp";
        if (length >= 12 && ascii(header, 4, "ftyp")) {
            for (int offset = 8; offset + 4 <= length; offset += 4) {
                if (offset == 12) continue;
                if (ascii(header, offset, "avif") || ascii(header, offset, "avis")) return "image/avif";
            }
            for (int offset = 8; offset + 4 <= length; offset += 4) {
                if (offset == 12) continue;
                if (ascii(header, offset, "heic") || ascii(header, offset, "heix")
                        || ascii(header, offset, "hevc") || ascii(header, offset, "hevx")) return "image/heic";
            }
        }
        throw new IOException("无法识别采集图片的实际格式，已暂停本条上传");
    }

    private static boolean ascii(byte[] data, int offset, String expected) {
        for (int index = 0; index < expected.length(); index++) {
            if (data[offset + index] != (byte) expected.charAt(index)) return false;
        }
        return true;
    }

    private static SharedPreferences settings(Context context) {
        return context.getSharedPreferences("collector_settings", Context.MODE_PRIVATE);
    }

    private static void changed(Context context) {
        SharedPreferences preferences = context.getSharedPreferences("collector_records", Context.MODE_PRIVATE);
        preferences.edit().putLong("generation", preferences.getLong("generation", 0) + 1).apply();
    }

    private static File directory(Context context) {
        File directory = new File(context.getFilesDir(), "records");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("无法建立采集记录目录");
        return directory;
    }

    private static File recordFile(Context context, String id) {
        return new File(directory(context), id + ".json");
    }

    private static JSONObject read(File file) {
        try (InputStream input = new AtomicFile(file).openRead()) {
            return new JSONObject(readText(input, 32 * 1024 * 1024));
        } catch (IOException | JSONException exception) {
            return null;
        }
    }

    private static void write(File destination, JSONObject object) throws IOException {
        AtomicFile file = new AtomicFile(destination);
        FileOutputStream output = null;
        try {
            output = file.startWrite();
            output.write(object.toString().getBytes(StandardCharsets.UTF_8));
            file.finishWrite(output);
        } catch (IOException exception) {
            if (output != null) file.failWrite(output);
            throw exception;
        }
    }

    private static String readText(InputStream stream, int limit) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int size;
        while ((size = stream.read(buffer)) != -1) {
            if (bytes.size() + size > limit) throw new IOException("服务器响应过大");
            bytes.write(buffer, 0, size);
        }
        return bytes.toString("UTF-8");
    }

    private static void text(OutputStream stream, String value) throws IOException {
        stream.write(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String fileHash(File file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new FileInputStream(file)) {
                byte[] buffer = new byte[65536];
                int size;
                while ((size = input.read(buffer)) != -1) digest.update(buffer, 0, size);
            }
            return hex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format(Locale.US, "%02x", value & 255));
        return result.toString();
    }

    private static String now() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US);
        format.setTimeZone(TimeZone.getDefault());
        return format.format(new Date());
    }

    private static String friendlyError(Exception error) {
        if (error instanceof java.net.SocketTimeoutException) return "回传超时";
        if (error instanceof java.net.UnknownHostException) return "无法连接回传服务器";
        if (error instanceof javax.net.ssl.SSLException) return "回传服务器的 HTTPS 证书未通过验证";
        if (error instanceof JSONException) return "服务器确认信息不是有效的 JSON";
        return error.getMessage() == null ? "回传失败" : error.getMessage();
    }
}
