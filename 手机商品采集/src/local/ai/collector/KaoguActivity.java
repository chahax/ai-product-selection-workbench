package local.ai.collector;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Base64;
import android.view.View;
import android.view.WindowInsets;
import android.webkit.CookieManager;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.HttpsURLConnection;

/** Own-account web login and native HTTPS access to the same site's product APIs. */
public final class KaoguActivity extends Activity {
    private static final String SITE = "https://www.kaogujia.com";
    private static final String LIST_PAGE = SITE + "/darenSquare/productList";
    private static final String API = "https://service.kaogujia.com";
    private static final String QR_ITEM = "kaogu_qr_item";
    private static final String PREFS = "kaogu_mobile";
    private static final int GREEN = Color.rgb(20, 106, 85);
    private static final int INK = Color.rgb(29, 45, 39);
    private static final int MUTED = Color.rgb(94, 111, 102);
    private static final int BG = Color.rgb(244, 247, 245);
    private static final int MAX_RESPONSE = 8 * 1024 * 1024;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService workers = Executors.newSingleThreadExecutor();
    // These are credentials of this WebView only. Never persist, export, or display them.
    private final Map<String, String> capturedHeaders = new HashMap<>();
    private String sessionToken = "";
    private String sessionUserAgent = "";
    private WebView web;
    private TextView status;
    private LinearLayout fields;
    private EditText keyword, period, page, limit, sortField, sort;
    private Button fetchButton, startButton;
    private boolean busy;
    private boolean destroyed;
    private boolean waitingSession;
    private int generation;
    private int sessionAttempt;
    private long sessionWaitStarted;
    private JSONObject pendingItem;

    public static void open(Context context) {
        context.startActivity(new Intent(context, KaoguActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
    }

    public static void requestQr(Context context, JSONObject item) {
        context.startActivity(new Intent(context, KaoguActivity.class)
                .putExtra(QR_ITEM, item.toString())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        makeUi();
        if (state != null && state.getString(QR_ITEM) != null) {
            getIntent().putExtra(QR_ITEM, state.getString(QR_ITEM));
        }
        web.loadUrl(LIST_PAGE);
        consumeIntent(getIntent());
    }

    @Override public void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        consumeIntent(intent);
    }

    private void makeUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(12), dp(8), dp(12), 0);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            view.setPadding(dp(12), bars.top + dp(8), dp(12), bars.bottom);
            return insets;
        });
        setContentView(root);
        TextView title = text("考古加 · 手机选品", 20, INK);
        root.addView(title);
        status = text("在下面登录考古加。登录状态保存在本机，请保留此应用。", 13, MUTED);
        status.setPadding(0, dp(6), 0, dp(6));
        root.addView(status);

        LinearLayout toolbar = row();
        toolbar.addView(button("登录考古加", v -> {
            if (busy) return;
            sessionToken = "";
            web.loadUrl(SITE + "/loginPage");
            setStatus("请在网页内完成登录，完成后读取商品。验证码由你本人操作。", false);
        }), weighted());
        toolbar.addView(button("筛选条件", v -> fields.setVisibility(fields.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE)), weighted());
        toolbar.addView(button("返回采集器", v -> {
            if (pendingItem != null) reportFailure("二维码读取已暂停，请返回考古加后重试。");
            startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        }), weighted());
        root.addView(toolbar);

        ScrollView filterScroll = new ScrollView(this);
        fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        filterScroll.addView(fields);
        JSONObject saved = parseObject(getSharedPreferences(PREFS, MODE_PRIVATE).getString("settings", "{}"));
        keyword = field(fields, "关键词", saved.optString("keyword", "吹风机"), false);
        LinearLayout numbers = row();
        fields.addView(numbers);
        period = field(numbers, "周期 period", String.valueOf(saved.optInt("period", 1)), true);
        page = field(numbers, "页码", String.valueOf(saved.optInt("page", 1)), true);
        limit = field(numbers, "每页条数", String.valueOf(saved.optInt("limit", 50)), true);
        LinearLayout sorting = row();
        fields.addView(sorting);
        sortField = field(sorting, "排序字段", saved.optString("sort_field", "sales"), false);
        sort = field(sorting, "排序 0 / 1", String.valueOf(saved.optInt("sort", 0)), true);
        root.addView(filterScroll, new LinearLayout.LayoutParams(-1, dp(190)));
        LinearLayout actions = row();
        fetchButton = button("读取这一页", v -> fetchList());
        startButton = button("开始采集", v -> {
            if (busy) return;
            if (CollectorService.startBatch(this)) setStatus("批次已开始，正在准备本件二维码。", false);
            else setStatus("请先读取商品名单，并开启采集辅助服务。", true);
        });
        actions.addView(fetchButton, weighted());
        actions.addView(startButton, weighted());
        root.addView(actions);
        fields.setVisibility(View.GONE);
        filterScroll.setVisibility(View.GONE);
        // Hide the entire filter viewport when collapsed, not only its child.
        toolbar.getChildAt(1).setOnClickListener(v -> {
            boolean show = filterScroll.getVisibility() != View.VISIBLE;
            fields.setVisibility(show ? View.VISIBLE : View.GONE);
            filterScroll.setVisibility(show ? View.VISIBLE : View.GONE);
        });

        web = new WebView(this);
        WebSettings settings = web.getSettings();
        sessionUserAgent = settings.getUserAgentString();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setSupportMultipleWindows(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSafeBrowsingEnabled(true);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame()) return false;
                Uri uri = request.getUrl();
                boolean allowed = "https".equals(uri.getScheme()) && isSiteHost(uri.getHost());
                if (!allowed) setStatus("已保留考古加页面。请在抖音商城内由采集流程打开商品。", true);
                return !allowed;
            }

            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if ("https".equals(uri.getScheme()) && "service.kaogujia.com".equals(uri.getHost()) && uri.getPath() != null && uri.getPath().startsWith("/api/")) {
                    synchronized (capturedHeaders) {
                        for (Map.Entry<String, String> h : request.getRequestHeaders().entrySet()) {
                            String name = h.getKey().toLowerCase(Locale.ROOT);
                            if (name.equals("authorization") || name.equals("x-common") || name.equals("version_code")
                                    || name.equals("x-utm-source") || name.equals("x-utm-term")) {
                                if (safeHeader(h.getValue())) capturedHeaders.put(name, h.getValue());
                            }
                        }
                    }
                }
                return null;
            }

            @Override public void onPageFinished(WebView view, String url) {
                CookieManager.getInstance().flush();
                if (pendingItem != null && !waitingSession && !busy) prepareQr();
            }

            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) setStatus("考古加网页没有加载成功，请检查网络后重新打开。", true);
            }

            @Override public void onReceivedSslError(WebView view, SslErrorHandler callback, SslError error) {
                callback.cancel();
                setStatus("网页证书验证失败，请检查网络和手机时间。", true);
            }
        });
        root.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
    }

    private void consumeIntent(Intent intent) {
        String raw = intent.getStringExtra(QR_ITEM);
        if (raw == null) return;
        intent.removeExtra(QR_ITEM);
        generation++;
        sessionAttempt++;
        sessionWaitStarted = 0;
        busy = false;
        waitingSession = false;
        pendingItem = parseObject(raw);
        if (!validItem(pendingItem) || pendingItem.optString("batch_id").isEmpty() || pendingItem.optString("qr_request_id").isEmpty()) {
            reportFailure("本件商品任务无效，请重新读取名单。");
            return;
        }
        setStatus("正在通过考古加接口准备本件商品二维码…", false);
        handler.postDelayed(this::prepareQr, 250);
    }

    private void fetchList() {
        if (busy || pendingItem != null) return;
        final JSONObject config;
        try {
            config = new JSONObject();
            config.put("keyword", keyword.getText().toString().trim());
            config.put("period", integer(period, 1, 3650));
            config.put("page", integer(page, 1, 10000));
            config.put("limit", integer(limit, 1, 50));
            String field = sortField.getText().toString().trim();
            if (!field.matches("[A-Za-z0-9_.]{1,50}")) throw new IllegalArgumentException();
            config.put("sort_field", field);
            config.put("sort", integer(sort, 0, 1));
            config.put("request_mode", "native_https");
        } catch (Exception error) {
            setStatus("请检查筛选：每页 1–50 条，页码大于零，排序填写 0 或 1。", true);
            return;
        }
        final int run = ++generation;
        setBusy(true);
        setStatus("正在读取本机登录会话并请求商品列表…", false);
        obtainSession(run, headers -> workers.execute(() -> {
            try {
                String path = "/api/sku/search";
                String query = "?limit=" + config.optInt("limit") + "&page=" + config.optInt("page")
                        + "&sort_field=" + Uri.encode(config.optString("sort_field")) + "&sort=" + config.optInt("sort");
                JSONObject body = new JSONObject().put("keyword", config.optString("keyword")).put("period", config.optInt("period"));
                Object result = apiRequest(path, query, body, headers);
                JSONArray source = result instanceof JSONArray ? (JSONArray) result : ((JSONObject) result).optJSONArray("items");
                if (source == null) throw new IllegalStateException("shape");
                JSONArray normalized = normalizeRows(source);
                if (normalized.length() == 0) {
                    post(run, () -> { setBusy(false); setStatus("这一页没有可采集商品，请检查筛选条件或账号权限。", true); });
                    return;
                }
                post(run, () -> {
                    try {
                        BatchStore.create(this, normalized, config);
                        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("settings", config.toString()).apply();
                        setBusy(false);
                        setStatus("已保存 " + normalized.length() + " 件商品名单。确认后点击“开始采集”。", false);
                    } catch (Exception error) { fail(run, "商品名单保存失败，请稍后重试。"); }
                });
            } catch (AuthFailure error) { post(run, () -> needLogin("考古加登录已失效，请在下面重新登录。")); }
            catch (Exception error) { post(run, () -> fail(run, "商品接口读取失败，请确认网页能显示商品和账号拥有相应权限后重试。")); }
        }));
    }

    private interface SessionReady { void accept(Map<String, String> headers); }

    private void obtainSession(int run, SessionReady ready) {
        if (!active(run)) return;
        String currentToken = ownToken();
        if (currentToken.isEmpty()) { needLogin("请先在下面完成考古加登录，登录后再继续。"); return; }
        Map<String, String> cached;
        synchronized (capturedHeaders) { cached = new HashMap<>(capturedHeaders); }
        // Background WebViews may suspend JS callbacks. Subsequent items reuse the verified
        // in-memory device session and the current cookie, without evaluating any webpage JS.
        if (currentToken.equals(sessionToken) && validDeviceHeader(cached.get("x-common"))) {
            cached.put("authorization", "Bearer " + currentToken);
            if (!cached.containsKey("version_code")) cached.put("version_code", "3.1");
            cached.put("User-Agent", sessionUserAgent);
            waitingSession = false;
            sessionWaitStarted = 0;
            ready.accept(cached);
            return;
        }
        sessionToken = "";
        if (!isSitePage()) { waitForSession(run); return; }
        if (pendingItem != null && sessionWaitStarted == 0) sessionWaitStarted = android.os.SystemClock.elapsedRealtime();
        final long remaining = pendingItem == null ? 1200 : 15000 - (android.os.SystemClock.elapsedRealtime() - sessionWaitStarted);
        if (remaining <= 0) { waitForSession(run); return; }
        waitingSession = true;
        final int attempt = ++sessionAttempt;
        // Even an invisible WebView must release the current item instead of retaining an old nonce.
        handler.postDelayed(() -> {
            if (active(run) && waitingSession && attempt == sessionAttempt) {
                sessionAttempt++;
                waitingSession = false;
                waitForSession(run);
            }
        }, Math.min(1200, remaining));
        web.evaluateJavascript("(() => {try {const d=JSON.parse(sessionStorage.getItem('kgj_web_device_id')||'{}');return {device:d.deviceId||'',path:location.pathname};}catch(e){return {};}})()", value -> {
            if (!active(run) || attempt != sessionAttempt) return;
            sessionAttempt++;
            waitingSession = false;
            JSONObject state = parseEvaluation(value);
            Map<String, String> headers;
            synchronized (capturedHeaders) { headers = new HashMap<>(capturedHeaders); }
            String token = ownToken();
            if (token.isEmpty()) { needLogin("请先在下面完成考古加登录，登录后再继续。"); return; }
            // Prefer the current cookie to an earlier request's token after a user relogin.
            headers.put("authorization", "Bearer " + token);
            String device = state.optString("device");
            if (device.matches("[A-Za-z0-9_-]{6,128}")) headers.put("x-common", "x-device=" + device);
            if (!validDeviceHeader(headers.get("x-common"))) {
                waitForSession(run);
                return;
            }
            if (!headers.containsKey("version_code")) headers.put("version_code", "3.1");
            headers.put("User-Agent", sessionUserAgent);
            synchronized (capturedHeaders) {
                capturedHeaders.put("authorization", "Bearer " + token);
                capturedHeaders.put("x-common", headers.get("x-common"));
                capturedHeaders.put("version_code", headers.get("version_code"));
            }
            sessionToken = token;
            sessionWaitStarted = 0;
            ready.accept(headers);
        });
    }

    private void waitForSession(int run) {
        if (!active(run)) return;
        waitingSession = false;
        setBusy(false);
        if (pendingItem == null) {
            setStatus("考古加网页正在加载，请稍候再点击“读取这一页”。", false);
            return;
        }
        long now = android.os.SystemClock.elapsedRealtime();
        if (sessionWaitStarted == 0) sessionWaitStarted = now;
        if (now - sessionWaitStarted >= 15000) {
            fail(run, "考古加网页还未准备好，本次已暂停。网页加载后返回采集器继续批次。");
            return;
        }
        setStatus("考古加网页正在准备本机会话，稍后自动重试…", false);
        handler.postDelayed(() -> {
            if (active(run) && pendingItem != null && !busy && !waitingSession) prepareQr();
        }, Math.min(650, 15000 - (now - sessionWaitStarted)));
    }

    private void prepareQr() {
        if (destroyed || pendingItem == null || busy || waitingSession) return;
        final int run = generation;
        final JSONObject item = parseObject(pendingItem.toString());
        setBusy(true);
        obtainSession(run, headers -> workers.execute(() -> {
            try {
                Object result = apiRequest("/api/sku/detail/" + item.optString("kaogujia_key"), "", null, headers);
                if (!(result instanceof JSONObject)) throw new IllegalStateException("detail");
                JSONObject detail = (JSONObject) result;
                String payload = detail.opt("qr_code") instanceof String ? detail.optString("qr_code") : "";
                if (!matchesProduct(payload, item.optString("product_id"))) {
                    post(run, () -> fail(run, "详情接口未返回与本件商品对应的二维码，已暂停以免采错商品。"));
                    return;
                }
                item.put("qr_source", "kaogujia_detail_api.qr_code");
                item.put("qr_code", payload);
                // Current official product detail component uses detailResult.cover for its main image.
                String cover = detail.optString("cover");
                item.put("main_image_url", cover);
                java.util.ArrayList<String> fields = new java.util.ArrayList<>();
                java.util.Iterator<String> names = detail.keys();
                while (names.hasNext()) fields.add(names.next());
                java.util.Collections.sort(fields);
                item.put("detail_field_names", new JSONArray(fields));
                // The verified detail component exposes no product-main-video mapping. Do not guess one.
                item.put("video_status", "not_mapped_from_detail_api");
                post(run, () -> setStatus("已取得本件二维码，正在保存考古加主图…", false));
                item.put("main_image", KaoguAssets.mainImage(getApplicationContext(), item, cover));
                byte[] png = QrAlbum.render(payload);
                post(run, () -> {
                    try {
                        // The entire payload and task identity, including batch_id, are preserved.
                        BatchStore.acceptQr(this, item, png);
                        pendingItem = null;
                        fetchButton.setText("读取这一页");
                        fetchButton.setOnClickListener(v -> fetchList());
                        setBusy(false);
                        setStatus("二维码已交给采集流程，正在返回抖音商城。", false);
                    } catch (Exception error) { fail(run, "二维码图片保存失败，本件已暂停。"); }
                });
            } catch (AuthFailure error) { post(run, () -> needLogin("考古加登录已失效，请在下面重新登录。")); }
            catch (Exception error) { post(run, () -> fail(run, "本件商品二维码接口读取失败，请检查考古加登录或稍后重试。")); }
        }));
    }

    private Object apiRequest(String path, String query, JSONObject body, Map<String, String> headers) throws Exception {
        if (!path.matches("/api/sku/(search|detail/[A-Za-z0-9_-]{1,120})")) throw new IllegalArgumentException();
        HttpsURLConnection connection = (HttpsURLConnection) new URL(API + path + query).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(20000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestMethod(body == null ? "GET" : "POST");
        connection.setRequestProperty("Accept", "application/json, text/plain, */*");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Origin", SITE);
        connection.setRequestProperty("Referer", LIST_PAGE);
        for (Map.Entry<String, String> header : headers.entrySet()) {
            if (safeHeader(header.getValue())) connection.setRequestProperty(header.getKey(), header.getValue());
        }
        try {
            if (body != null) {
                connection.setDoOutput(true);
                byte[] encoded = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(encoded.length);
                try (OutputStream out = connection.getOutputStream()) { out.write(encoded); }
            }
            int code = connection.getResponseCode();
            if (code == 401 || code == 403 || code == 409) throw new AuthFailure();
            if (code != 200) throw new IllegalStateException("http");
            JSONObject envelope;
            try (InputStream in = connection.getInputStream()) { envelope = new JSONObject(new String(readBounded(in), StandardCharsets.UTF_8)); }
            int business = envelope.optInt("code");
            if (business == 401 || business == 403 || business == 409) throw new AuthFailure();
            if (business != 200 || !envelope.optBoolean("success")) throw new IllegalStateException("business");
            Object data = envelope.opt("data");
            if (data instanceof JSONObject || data instanceof JSONArray) return data;
            if (!(data instanceof String) || ((String) data).isEmpty()) throw new IllegalStateException("empty");
            return decodeApiData(path, (String) data);
        } finally { connection.disconnect(); }
    }

    /** Mirrors the public site's response interceptor, which derives its AES key from the relative API path. */
    static Object decodeApiData(String path, String encoded) throws Exception {
        if (!path.startsWith("/api/") || path.indexOf('?') >= 0 || !StandardCharsets.US_ASCII.newEncoder().canEncode(path)) throw new IllegalArgumentException();
        String seed = Base64.encodeToString(path.getBytes(StandardCharsets.US_ASCII), Base64.NO_WRAP);
        seed = seed + seed + seed;
        byte[] ciphertext = Base64.decode(encoded, Base64.DEFAULT);
        if (ciphertext.length > 16 && new String(ciphertext, 0, 8, StandardCharsets.US_ASCII).equals("Salted__")) {
            ciphertext = java.util.Arrays.copyOfRange(ciphertext, 16, ciphertext.length);
        }
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(seed.substring(0, 16).getBytes(StandardCharsets.UTF_8), "AES"),
                new IvParameterSpec(seed.substring(12, 28).getBytes(StandardCharsets.UTF_8)));
        String plaintext = new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        Object parsed = new JSONTokener(plaintext).nextValue();
        if (!(parsed instanceof JSONArray) && !(parsed instanceof JSONObject)) throw new IllegalStateException("decoded-shape");
        return parsed;
    }

    static JSONArray normalizeRows(JSONArray input) throws Exception {
        JSONArray rows = new JSONArray();
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < input.length(); i++) {
            JSONObject row = input.optJSONObject(i);
            if (row == null) continue;
            String key = row.optString("product_id"), title = row.optString("title").trim(), url = row.optString("product_url");
            String id;
            try { id = Uri.parse(url).getQueryParameter("id"); }
            catch (Exception invalidUrl) { continue; }
            if (!key.matches("[A-Za-z0-9_-]{1,120}") || title.isEmpty() || id == null || !matchesProduct(url, id) || !seen.add(id)) continue;
            JSONObject item = new JSONObject();
            item.put("order", rows.length() + 1);
            item.put("kaogujia_key", key);
            item.put("product_id", id);
            item.put("title", title);
            item.put("shop_name", row.optString("shop_name"));
            item.put("url", url);
            String cover = row.optString("cover");
            item.put("list_image_url", "https".equals(Uri.parse(cover).getScheme()) ? cover : "");
            item.put("detail_link", SITE + "/productDetails/productOverview/" + Uri.encode(key));
            item.put("source", "kaogujia_native_api");
            rows.put(item);
        }
        return rows;
    }

    private static boolean matchesProduct(String url, String id) {
        try {
            Uri uri = Uri.parse(url);
            return id.matches("[0-9]{10,25}") && "https".equals(uri.getScheme()) && "haohuo.jinritemai.com".equals(uri.getHost())
                    && "/ecommerce/trade/detail/index.html".equals(uri.getPath()) && id.equals(uri.getQueryParameter("id"));
        } catch (Exception error) { return false; }
    }

    private static boolean validItem(JSONObject item) {
        return item != null && item.optString("kaogujia_key").matches("[A-Za-z0-9_-]{1,120}")
                && matchesProduct(item.optString("url"), item.optString("product_id"));
    }

    private String ownToken() {
        String cookies = CookieManager.getInstance().getCookie(SITE);
        if (cookies == null) return "";
        for (String part : cookies.split(";")) {
            int at = part.indexOf('=');
            if (at > 0 && "KGJ-Token".equals(part.substring(0, at).trim())) {
                String token = Uri.decode(part.substring(at + 1).trim());
                return safeHeader(token) ? token : "";
            }
        }
        return "";
    }

    private boolean isSitePage() {
        try { Uri uri = Uri.parse(web.getUrl()); return "https".equals(uri.getScheme()) && isSiteHost(uri.getHost()); }
        catch (Exception error) { return false; }
    }

    private static boolean isSiteHost(String host) { return "www.kaogujia.com".equals(host) || "kaogujia.com".equals(host); }
    private static boolean validDeviceHeader(String value) { return value != null && value.matches("x-device=[A-Za-z0-9_-]{6,128}"); }
    private static boolean safeHeader(String value) { return value != null && !value.isEmpty() && value.length() < 16000 && value.indexOf('\r') < 0 && value.indexOf('\n') < 0; }
    private static byte[] readBounded(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) { if (out.size() + read > MAX_RESPONSE) throw new IllegalStateException("size"); out.write(buffer, 0, read); }
        return out.toByteArray();
    }

    private void needLogin(String message) {
        sessionToken = "";
        waitingSession = false;
        setBusy(false);
        if (pendingItem != null) {
            reportFailure(message + " 登录后返回采集器继续批次。");
        } else setStatus(message, true);
    }

    private void fail(int run, String reason) {
        if (!active(run)) return;
        setBusy(false);
        setStatus(reason, true);
        if (pendingItem != null) reportFailure(reason);
    }

    private void reportFailure(String reason) {
        JSONObject requested = pendingItem;
        pendingItem = null;
        generation++;
        sessionAttempt++;
        sessionWaitStarted = 0;
        waitingSession = false;
        setBusy(false);
        fetchButton.setText("读取这一页");
        fetchButton.setOnClickListener(v -> fetchList());
        if (requested != null) BatchStore.browserFailure(this, requested, reason);
        else BatchStore.browserFailure(this, reason);
        setStatus(reason, true);
    }

    private boolean active(int run) { return !destroyed && !isFinishing() && run == generation; }
    private void post(int run, Runnable action) { handler.post(() -> { if (active(run)) action.run(); }); }
    private void setBusy(boolean value) { busy = value; fetchButton.setEnabled(!value); startButton.setEnabled(!value && pendingItem == null); }
    private void setStatus(String message, boolean error) {
        status.setText(message);
        status.setTextColor(error ? Color.rgb(170, 55, 45) : MUTED);
        getSharedPreferences("kaogu_browser_state", MODE_PRIVATE).edit()
                .putString("phase", message).putString("time", java.time.Instant.now().toString())
                .putLong("time_ms", System.currentTimeMillis()).putBoolean("has_pending", pendingItem != null).apply();
        if (pendingItem != null) BatchStore.browserProgress(this, pendingItem, message);
    }
    private static JSONObject parseObject(String raw) { try { return new JSONObject(raw); } catch (Exception error) { return new JSONObject(); } }
    private static JSONObject parseEvaluation(String raw) { try { Object value = new JSONTokener(raw).nextValue(); return value instanceof JSONObject ? (JSONObject) value : parseObject(String.valueOf(value)); } catch (Exception error) { return new JSONObject(); } }
    private static int integer(EditText input, int min, int max) { int n = Integer.parseInt(input.getText().toString().trim()); if (n < min || n > max) throw new IllegalArgumentException(); return n; }
    private int dp(int n) { return Math.round(getResources().getDisplayMetrics().density * n); }
    private LinearLayout row() { LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); return row; }
    private LinearLayout.LayoutParams weighted() { return new LinearLayout.LayoutParams(0, -2, 1); }
    private TextView text(String value, int size, int color) { TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color); return view; }
    private Button button(String label, View.OnClickListener action) { Button view = new Button(this); view.setText(label); view.setTextSize(12); view.setAllCaps(false); view.setTextColor(GREEN); view.setOnClickListener(action); return view; }
    private EditText field(LinearLayout parent, String label, String value, boolean number) {
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(3), 0, dp(3), 0);
        box.addView(text(label, 11, MUTED));
        EditText input = new EditText(this); input.setSingleLine(true); input.setTextSize(13); input.setText(value);
        input.setInputType(number ? InputType.TYPE_CLASS_NUMBER : InputType.TYPE_CLASS_TEXT);
        box.addView(input, new LinearLayout.LayoutParams(-1, dp(40)));
        if (parent.getOrientation() == LinearLayout.HORIZONTAL) parent.addView(box, weighted()); else parent.addView(box);
        return input;
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        if (pendingItem != null) state.putString(QR_ITEM, pendingItem.toString());
        super.onSaveInstanceState(state);
    }
    @Override protected void onPause() { CookieManager.getInstance().flush(); super.onPause(); }
    @Override public void onBackPressed() {
        if (pendingItem != null) reportFailure("已返回采集器，本件二维码读取暂停。");
        super.onBackPressed();
    }
    @Override protected void onDestroy() {
        destroyed = true; generation++;
        handler.removeCallbacksAndMessages(null);
        workers.shutdownNow();
        synchronized (capturedHeaders) { capturedHeaders.clear(); }
        sessionToken = "";
        if (web != null) web.destroy();
        super.onDestroy();
    }
    private static final class AuthFailure extends Exception { }
}
