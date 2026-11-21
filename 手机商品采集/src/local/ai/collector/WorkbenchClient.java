package local.ai.collector;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Polls only for an opted-in foreground phone and reports already assigned work after leaving the screen. */
final class WorkbenchClient {
    private static WorkbenchClient instance;
    private final Context app;
    private final SharedPreferences state;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile WorkbenchConnection connection;
    private volatile boolean foreground;
    private volatile boolean standby;
    private volatile String message = "";

    static synchronized WorkbenchClient get(Context context) {
        if (instance == null) instance = new WorkbenchClient(context.getApplicationContext());
        return instance;
    }

    private WorkbenchClient(Context app) {
        this.app = app;
        state = app.getSharedPreferences("workbench_tasks", Context.MODE_PRIVATE);
        String token = state.getString("token", "");
        if (!token.isEmpty()) {
            try {
                connection = new WorkbenchConnection(state.getString("address", ""), state.getString("fingerprint", ""), token);
            } catch (Exception error) { message = friendly(error); }
        }
        worker.scheduleWithFixedDelay(this::pollSafely, 0, 4, TimeUnit.SECONDS);
    }

    WorkbenchConnection connection() { return connection; }
    boolean isStandby() { return standby; }
    boolean hasJob() { return state.contains("active_job"); }
    String jobId() { return readJob().optString("id"); }

    void setForeground(boolean value) {
        foreground = value;
        worker.execute(() -> pollSafely(!value));
    }

    void setStandby(boolean value) {
        standby = value;
        message = "";
        worker.execute(this::pollSafely);
    }

    String statusMessage() {
        if (!message.isEmpty()) return message;
        if (!paired()) return "先在工作台选择连接手机，使用配对码连接。";
        if (hasJob()) return "工作台任务已分配，可在下方查看采集进度。";
        if (!standby) return "已连接工作台。开启待命后，可以接收网页下发的任务。";
        if (!CollectorService.isConnected()) return "请先开启无障碍服务，再接收工作台任务。";
        try {
            if (!CollectorService.canAcceptWorkbenchJob(app)) return "请先完成或结束手机上已有的采集任务。";
        } catch (RuntimeException error) { return friendly(error); }
        return "等待工作台任务，请保持手机解锁并停留在本应用。";
    }

    boolean paired() { return connection != null; }

    void pair(String address, String fingerprint, String code, Consumer<String> result) {
        worker.execute(() -> {
            String failure = "";
            try {
                if (hasJob() || RecordStore.hasPendingWorkbenchUploads(app)) {
                    throw new IllegalStateException("请先完成当前工作台任务和待回传记录");
                }
                if (!code.trim().matches("[0-9]{6}")) throw new IllegalArgumentException("请输入工作台提供的六位配对码");
                WorkbenchConnection target = new WorkbenchConnection(address, fingerprint, "");
                String deviceId = RecordStore.deviceId(app);
                JSONObject response = target.post("/api/mobile/pair", new JSONObject()
                        .put("code", code.trim()).put("device_id", deviceId)
                        .put("name", Build.MANUFACTURER + " " + Build.MODEL));
                String token = response.optString("token");
                if (token.isEmpty() || !deviceId.equals(response.optString("device_id"))) {
                    throw new IOException("工作台未确认此手机的配对");
                }
                WorkbenchConnection paired = new WorkbenchConnection(target.address, target.fingerprint, token);
                if (!state.edit().putString("address", paired.address).putString("fingerprint", paired.fingerprint)
                        .putString("token", token).commit()) throw new IOException("配对信息未能保存，请重新配对");
                connection = paired;
                standby = false;
                message = "";
            } catch (Exception error) { failure = friendly(error); }
            String callback = failure;
            main.post(() -> result.accept(callback));
        });
    }

    private boolean phoneReady() {
        PowerManager power = app.getSystemService(PowerManager.class);
        KeyguardManager keyguard = app.getSystemService(KeyguardManager.class);
        return foreground && standby && paired() && power != null && power.isInteractive()
                && keyguard != null && !keyguard.isKeyguardLocked() && CollectorService.isConnected();
    }

    boolean canStart(String jobId) {
        return phoneReady() && jobId.equals(jobId());
    }

    void startFailed(String jobId, String error) {
        worker.execute(() -> {
            JSONObject job = readJob();
            if (!jobId.equals(job.optString("id"))) return;
            try { job.put("start_error", error); saveJob(job); }
            catch (JSONException | IOException failure) { message = friendly(failure); }
        });
    }

    private JSONObject readJob() {
        String raw = state.getString("active_job", "");
        if (raw.isEmpty()) return new JSONObject();
        try { return new JSONObject(raw); }
        catch (JSONException error) { throw new IllegalStateException("工作台任务进度暂时无法读取", error); }
    }

    private void saveJob(JSONObject job) throws IOException {
        if (!state.edit().putString("active_job", job.toString()).commit()) throw new IOException("工作台任务进度未能保存");
    }

    private void pollSafely() {
        pollSafely(false);
    }

    private void pollSafely(boolean sendUnavailable) {
        if (connection == null || (!foreground && !hasJob() && !sendUnavailable)) return;
        try { poll(); }
        catch (Exception error) { message = friendly(error); }
    }

    private void poll() throws IOException, JSONException {
        WorkbenchConnection target = connection;
        JSONObject job = readJob();
        boolean ready = job.length() == 0 && phoneReady() && CollectorService.canAcceptWorkbenchJob(app);
        JSONObject reply = target.post("/api/mobile/jobs/claim", new JSONObject().put("ready", ready));
        JSONObject assigned = reply.optJSONObject("job");
        if (job.length() == 0 && assigned != null) {
            String id = assigned.optString("id");
            if (!id.matches("[A-Za-z0-9_-]{1,120}")) throw new IOException("工作台返回了无效的任务编号");
            job = new JSONObject().put("id", id).put("period", assigned.optString("period"))
                    .put("limit", assigned.optInt("limit", 0)).put("category", assigned.optString("category"))
                    .put("sequence", assigned.optLong("sequence", 0));
            saveJob(job);
        }
        if (job.length() != 0) {
            if (assigned != null && !job.optString("id").equals(assigned.optString("id"))) {
                throw new IOException("工作台与手机的当前任务编号不一致");
            }
            report(target, job);
        }
        message = "";
    }

    private void report(WorkbenchConnection target, JSONObject job) throws IOException, JSONException {
        String id = job.getString("id");
        JSONObject progress = job.optJSONObject("pending");
        if (progress == null) {
            progress = snapshot(job);
            if (progress.toString().equals(job.optString("reported"))) return;
            job.put("reported", progress.toString());
            long sequence = job.optLong("sequence") + 1;
            job.put("sequence", sequence);
            job.put("pending", progress.put("sequence", sequence));
            saveJob(job);
        }
        target.post("/api/mobile/jobs/" + id + "/progress", progress);
        String status = progress.optString("status");
        if ("completed".equals(status) || "failed".equals(status)) {
            if (!state.edit().remove("active_job").commit()) throw new IOException("工作台任务完成状态未能保存");
        } else {
            job.remove("pending");
            saveJob(job);
        }
    }

    private JSONObject snapshot(JSONObject job) throws JSONException {
        String id = job.getString("id");
        JSONObject batch = BatchStore.summary(app);
        String error = job.optString("start_error");
        if (!id.equals(batch.optString("workbench_job_id"))) {
            int limit = job.optInt("limit");
            String period = job.optString("period");
            String category = job.optString("category");
            if (KaoguAppNavigator.normalizeDateRange(period).isEmpty() || limit < 1 || limit > 200 || category.isEmpty()) {
                error = "工作台任务日期、类目或商品数无效";
            }
            if (!error.isEmpty()) return new JSONObject().put("status", "failed").put("error", shorten(error));
            if (canStart(id) && CollectorService.canAcceptWorkbenchJob(app)) {
                CollectorService.startWorkbenchJob(app, id, period, limit, category);
            }
            return new JSONObject().put("status", "waiting_phone").put("stage", "等待手机待命并启动采集");
        }

        String status = batch.optString("status");
        String phase = batch.optString("phase");
        int invalid = batch.optInt("invalid");
        JSONObject progress = new JSONObject().put("stage", shorten(phase))
                .put("current_title", shorten(batch.optString("title"))).put("total", batch.optInt("total"))
                .put("complete", batch.optInt("complete")).put("partial", batch.optInt("partial"))
                .put("failed", Math.max(0, batch.optInt("failed") - invalid)).put("invalid", invalid);

        RecordStore.JobUploads uploads = RecordStore.workbenchUploads(app, id);
        if (uploads.pending > 0) RecordStore.scheduleUpload(app);
        if ("complete".equals(status) || "stopped".equals(status)) {
            progress.put("expected_records", uploads.total);
            if (uploads.pending > 0) return progress.put("status", "uploading").put("stage", "采集已结束，正在回传结果");
            if ("complete".equals(status)) return progress.put("status", "completed");
            return progress.put("status", "failed").put("error", "手机上已结束此任务");
        }
        if ("paused".equals(status) || !CollectorService.isConnected()) {
            return progress.put("status", "paused").put("stage", shorten(CollectorService.isConnected()
                    ? phase : "无障碍服务暂未连接，请在手机确认后继续"));
        }
        return progress.put("status", "running");
    }

    private static String shorten(String value) { return value.length() > 200 ? value.substring(0, 200) : value; }

    private static String friendly(Exception error) {
        if (error instanceof javax.net.ssl.SSLException) return "工作台 HTTPS 证书未通过验证，请核对地址和指纹。";
        if (error instanceof java.net.SocketTimeoutException || error instanceof java.net.ConnectException
                || error instanceof java.net.UnknownHostException) return "暂时连不上工作台，手机会自动重试。";
        String value = error.getMessage();
        return value == null || value.length() > 200 ? "暂时无法与工作台通信，手机会自动重试。" : value;
    }
}
