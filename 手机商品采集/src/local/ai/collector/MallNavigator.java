package local.ai.collector;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.LuminanceSource;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.ResultPoint;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.common.GlobalHistogramBinarizer;
import com.google.zxing.multi.qrcode.QRCodeMultiReader;
import com.google.zxing.qrcode.QRCodeReader;

import org.json.JSONObject;

import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Read-only decisions for the Mall scanner. The service owns actions, deadlines and state. */
public final class MallNavigator {
    private static final String MALL = "com.ss.android.ugc.livelite";
    private static final Pattern ID = Pattern.compile("[0-9]{10,25}");
    private static final Set<String> STAGES = new HashSet<>(Arrays.asList(
            "mall_home", "scan_entry", "scan_mode", "scan_album", "scan_verify", "scan_result", "pdp_verify"));
    private static final Set<String> PICKERS = new HashSet<>(Arrays.asList(
            "com.android.providers.media.module", "com.google.android.providers.media.module",
            "com.android.documentsui", "com.google.android.documentsui"));
    private static final String[] BLOCKERS = {"安全验证", "身份验证", "请完成验证", "拖动滑块",
            "请先登录", "登录后继续", "请登录后", "授权照片", "访问照片和视频", "请允许访问相机"};
    private static final String[] PAGE_ERRORS = {"网络异常", "商品已下架", "商品不存在", "无法访问页面", "商品暂不可购买"};

    public static final class Step {
        public String nextStage = "";
        public String message = "";
        public Rect click;
        public boolean back, ready, needsScreenshot, retry;
        public String intervention = "";
        public int timeoutMs = 15000;
        public String timeoutMessage = "";
        public String identityBasis = "";
        public String identityTitle = "";
    }

    private static final class Label {
        String text;
        Rect bounds;
        boolean enabled;
        Label(String text, Rect bounds, boolean enabled) {
            this.text = text;
            this.bounds = new Rect(bounds);
            this.enabled = enabled;
        }
    }

    private static final class Code {
        String text;
        Rect bounds;
        Code(String text, Rect bounds) { this.text = text; this.bounds = bounds; }
    }

    private MallNavigator() { }

    /** A click/back advances nextStage only after the caller executes the action. */
    public static Step next(AccessibilityNodeInfo root, String stage, JSONObject item) {
        Step out = step(stage, "正在识别商城导航页面");
        if (!STAGES.contains(stage)) return blocked(out, "无法识别商城导航阶段");
        String expectedId = item == null ? "" : item.optString("product_id");
        if (!ID.matcher(expectedId).matches()) return blocked(out, "本件商品 ID 缺失或格式不正确");
        if (root == null || root.getPackageName() == null) return waiting(out, "等待商城页面出现");
        String packageName = root.getPackageName().toString();
        boolean externalPicker = PICKERS.contains(packageName);
        if (!MALL.equals(packageName) && !(externalPicker && (stage.equals("mall_home") || stage.equals("scan_album")
                || stage.equals("scan_verify") || stage.equals("scan_result") || stage.equals("pdp_verify")))) {
            return blocked(out, "商城被其他应用或系统窗口遮挡，请检查手机后继续");
        }
        Rect screen = new Rect();
        root.getBoundsInScreen(screen);
        List<Label> labels = new ArrayList<>();
        collect(root, labels, new HashSet<>(), 0);
        for (Label label : labels) {
            for (String blocker : BLOCKERS) if (label.text.contains(blocker)) return blocked(out, "商城要求处理“" + blocker + "”");
            for (String error : PAGE_ERRORS) if (label.text.equals(error)) {
                if (stage.equals("mall_home")) {
                    out.back = true;
                    out.message = "正在离开上一件的异常详情，返回首页准备本件扫码";
                    return out;
                }
                Step failure = blocked(out, error + "，可能需要重新获取商品二维码");
                if (error.equals("网络异常")) failure.click = unique(labels, screen, .20f, .85f, "刷新", "重新加载");
                return failure;
            }
        }
        DouyinAdapter.Snapshot product = MALL.equals(packageName) ? DouyinAdapter.read(root) : new DouyinAdapter.Snapshot();
        boolean picker = externalPicker || has(labels, "最近项目", "最近照片", "选择图片", "选择照片", "照片和视频", "所有照片", "最近使用");
        boolean pdp = product.openSpecs != null || has(labels, "商品详情", "商品评价", "商品参数", "进入店铺");
        if (stage.equals("mall_home")) {
            if (product.popup || product.gallery || pdp || picker || has(labels, "识别码", "从相册选择")) {
                out.back = true;
                out.message = "正在返回商城首页，刷新本件二维码的相册入口";
                return out;
            }
            out.nextStage = "scan_entry";
            out.click = unique(labels, screen, .0f, .32f, "拍照搜", "拍照搜索", "扫一扫", "扫码");
            return actionOrImage(out, "正在打开商城拍照搜");
        }
        if (stage.equals("scan_entry")) {
            out.nextStage = "scan_mode";
            out.click = unique(labels, screen, .0f, .35f, "识别码", "扫一扫", "扫码", "识别二维码");
            return actionOrImage(out, "正在切换到识别码模式");
        }
        if (stage.equals("scan_mode")) {
            out.nextStage = "scan_album";
            out.click = unique(labels, screen, .55f, 1f, "相册", "从相册选择", "打开相册");
            return actionOrImage(out, "正在打开扫码相册");
        }
        if (stage.equals("scan_album")) {
            out.nextStage = "scan_verify";
            // Some Mall album views have no accessible labels. The following
            // stage still requires a uniquely decoded product QR before clicking.
            return waiting(out, picker ? "相册已打开，正在准备核对本件二维码" : "正在通过二维码解码确认相册内容");
        }
        if (stage.equals("scan_verify")) {
            if (pdp || product.popup || product.gallery) return blocked(out, "二维码选择前相册列表已关闭");
            out.nextStage = "scan_result";
            out.needsScreenshot = true;
            out.retry = true;
            out.message = "正在解码相册图片，只选择商品 ID 匹配且唯一的二维码";
            out.timeoutMessage = "相册里未找到本件唯一二维码，不会直接点击第一张";
            return out;
        }
        if (stage.equals("scan_result")) {
            out.timeoutMs = 26000;
            if (externalPicker) return waiting(out, "等待系统相册返回商城扫码结果");
            if (pdp && !product.popup && !product.gallery) {
                out.nextStage = "pdp_verify";
                return waiting(out, "商品详情已打开，正在核对原商品身份");
            }
            out.nextStage = "pdp_verify";
            out.click = unique(labels, screen, .10f, .95f, "打开商品", "查看商品", "前往商品", "打开页面");
            return actionOrImage(out, "等待扫码结果，必要时确认二维码打开提示");
        }
        out.timeoutMs = 26000;
        if (externalPicker) return waiting(out, "等待系统相册返回商品详情");
        if (product.popup || product.gallery) return blocked(out, "商品身份尚未核对，请先保持商品详情页打开");
        String expectedTitle = first(item, "title", "product_title", "name");
        String expectedShop = first(item, "shop_name", "shop", "shop_title");
        String matchedTitle = "";
        for (Label label : labels) if (sameProductTitle(label.text, expectedTitle)) { matchedTitle = label.text; break; }
        boolean byId = false;
        for (Label label : labels) {
            String found = productId(label.text);
            if (expectedId.equals(found) || label.text.matches("商品ID[:：]?\\s*" + expectedId)) byId = true;
        }
        if (pdp && (byId || !matchedTitle.isEmpty())) {
            out.ready = true;
            out.identityTitle = matchedTitle.isEmpty() ? product.title : matchedTitle;
            boolean shopMatches = !expectedShop.isEmpty() && !exact(labels, expectedShop).isEmpty();
            out.identityBasis = byId ? "product_id" : shopMatches ? "source_qr_and_exact_title_shop" : "source_qr_and_exact_title";
            out.message = "已核对本件商品详情，可以开始采集规格价图";
            return out;
        }
        if (pdp && !product.title.isEmpty() && !expectedTitle.isEmpty() && !sameProductTitle(product.title, expectedTitle)) {
            return blocked(out, "扫码后商品标题与本件不一致，未开始采集");
        }
        return waiting(out, "等待商品详情及完整标题，身份未核对前不会采集");
    }

    /** Screenshot control matching uses the verified 540x1200 templates, scaled back to real pixels. */
    public static Rect findControl(Context context, Bitmap screen, String stage) throws Exception {
        if (context == null || !supportedFrame(screen)) return null;
        String file;
        int x0, x1, y0, y1;
        double limit;
        if (stage.equals("mall_home")) {
            file = "mall_home_camera.png"; x0 = 386; x1 = 407; y0 = 73; y1 = 157; limit = 15;
        } else if (stage.equals("scan_entry")) {
            file = "mall_scan_mode.png"; x0 = 429; x1 = 447; y0 = 69; y1 = 81; limit = 18;
        } else if (stage.equals("scan_mode")) {
            file = "mall_scan_album.png"; x0 = 402; x1 = 426; y0 = 884; y1 = 913; limit = 18;
        } else if (stage.equals("scan_result")) {
            return confirmation(context, screen);
        } else return null;
        Bitmap reference;
        try (InputStream input = context.getAssets().open("mall_navigation/" + file)) {
            reference = BitmapFactory.decodeStream(input);
        }
        if (reference == null) return null;
        Bitmap small = Bitmap.createScaledBitmap(screen, 540, 1200, true);
        try {
            Rect best = null;
            double score = limit;
            for (int y = y0; y < y1; y++) for (int x = x0; x < x1; x++) {
                double difference = difference(reference, small, x, y, false);
                if (difference < score) {
                    score = difference;
                    best = new Rect(x, y, x + reference.getWidth(), y + reference.getHeight());
                }
            }
            return best == null ? null : actual(best, screen);
        } finally {
            reference.recycle();
            if (small != screen) small.recycle();
        }
    }

    /** Never selects a first thumbnail: every candidate must decode to the expected ID. */
    public static Rect findQr(Bitmap screen, String expectedProductId) {
        if (!supportedFrame(screen) || !ID.matcher(expectedProductId == null ? "" : expectedProductId).matches()) return null;
        LuminanceSource source = luminance(screen);
        List<Code> codes = new ArrayList<>();
        addCodes(codes, decode(source), 0, 0, screen.getWidth(), screen.getHeight());
        int side = screen.getWidth() / 4;
        int top = Math.round(450f * screen.getHeight() / 2400f);
        for (int row = 0; row < 3; row++) for (int column = 0; column < 4; column++) {
            int x = column * side, y = top + row * side;
            if (y + side > screen.getHeight() || x + side > screen.getWidth()) continue;
            addCodes(codes, decode(source.crop(x, y, side, side)), x, y, side, side);
        }
        Rect found = null;
        for (Code code : codes) {
            if (!expectedProductId.equals(productId(code.text))) continue;
            if (found != null) return null;
            found = new Rect(code.bounds);
        }
        return found;
    }

    /** Used before writing PNGs to the album; requires one and only one readable QR. */
    public static String qrPayload(Bitmap png) {
        if (png == null || png.isRecycled() || png.getWidth() < 64 || png.getHeight() < 64
                || (long) png.getWidth() * png.getHeight() > 16000000) return "";
        Result[] results = decode(luminance(png));
        return results.length == 1 && productId(results[0].getText()) != null ? results[0].getText() : "";
    }

    /** Only the first-party product route used by the verified Kaogujia QR is accepted. */
    public static String productId(String payload) {
        try {
            URI uri = new URI(payload);
            if (!"https".equals(uri.getScheme()) || !"haohuo.jinritemai.com".equals(uri.getRawAuthority())
                    || !"/ecommerce/trade/detail/index.html".equals(uri.getPath()) || uri.getRawQuery() == null) return null;
            String id = null;
            for (String parameter : uri.getRawQuery().split("&")) {
                String[] pair = parameter.split("=", 2);
                if (!"id".equals(URLDecoder.decode(pair[0], "UTF-8"))) continue;
                if (id != null || pair.length != 2) return null;
                id = URLDecoder.decode(pair[1], "UTF-8");
            }
            return id != null && ID.matcher(id).matches() ? id : null;
        } catch (Exception invalid) { return null; }
    }

    private static Result[] decode(LuminanceSource source) {
        Result[] found = decodeBase(source);
        if (found.length > 0 || source.getWidth() > 500 || source.getHeight() > 500) return found;
        // Small album thumbnails can have finder patterns too thin for the
        // detector. Nearest-neighbour enlargement changes no QR information.
        byte[] original = source.getMatrix();
        for (int factor : new int[]{2, 4}) {
            int width = source.getWidth() * factor, height = source.getHeight() * factor;
            byte[] larger = new byte[width * height];
            for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                larger[y * width + x] = original[(y / factor) * source.getWidth() + x / factor];
            }
            found = decodeBase(new GraySource(width, height, larger));
            if (found.length == 0) continue;
            Result[] mapped = new Result[found.length];
            for (int i = 0; i < found.length; i++) {
                ResultPoint[] points = found[i].getResultPoints();
                ResultPoint[] actual = points == null ? null : new ResultPoint[points.length];
                if (points != null) for (int j = 0; j < points.length; j++) {
                    if (points[j] != null) actual[j] = new ResultPoint(points[j].getX() / factor, points[j].getY() / factor);
                }
                mapped[i] = new Result(found[i].getText(), found[i].getRawBytes(), actual, found[i].getBarcodeFormat());
            }
            return mapped;
        }
        return found;
    }

    private static final class GraySource extends LuminanceSource {
        private final byte[] data;
        GraySource(int width, int height, byte[] data) { super(width, height); this.data = data; }
        @Override public byte[] getMatrix() { return data; }
        @Override public byte[] getRow(int y, byte[] row) {
            if (row == null || row.length < getWidth()) row = new byte[getWidth()];
            System.arraycopy(data, y * getWidth(), row, 0, getWidth());
            return row;
        }
    }

    private static Result[] decodeBase(LuminanceSource source) {
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        hints.put(DecodeHintType.CHARACTER_SET, "UTF-8");
        for (int pass = 0; pass < 4; pass++) {
            LuminanceSource input = pass % 2 == 0 ? source : source.invert();
            BinaryBitmap bitmap = new BinaryBitmap(pass < 2 ? new HybridBinarizer(input) : new GlobalHistogramBinarizer(input));
            try { return new QRCodeMultiReader().decodeMultiple(bitmap, hints); }
            catch (Exception notMultiple) {
                try { return new Result[]{new QRCodeReader().decode(bitmap, hints)}; }
                catch (Exception notSingle) { /* An unreadable image is never a click target. */ }
            }
        }
        return new Result[0];
    }

    private static LuminanceSource luminance(Bitmap bitmap) {
        int width = bitmap.getWidth(), height = bitmap.getHeight();
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        return new RGBLuminanceSource(width, height, pixels);
    }

    private static void addCodes(List<Code> codes, Result[] results, int offsetX, int offsetY, int width, int height) {
        for (Result result : results) {
            ResultPoint[] points = result.getResultPoints();
            if (points == null || points.length < 3) continue;
            float left = width, top = height, right = 0, bottom = 0;
            for (ResultPoint point : points) if (point != null) {
                left = Math.min(left, point.getX()); right = Math.max(right, point.getX());
                top = Math.min(top, point.getY()); bottom = Math.max(bottom, point.getY());
            }
            Rect bounds = new Rect(offsetX + Math.max(0, (int) left), offsetY + Math.max(0, (int) top),
                    offsetX + Math.min(width, (int) Math.ceil(right)), offsetY + Math.min(height, (int) Math.ceil(bottom)));
            if (bounds.width() < 18 || bounds.height() < 18) continue;
            boolean duplicate = false;
            for (Code previous : codes) {
                if (previous.text.equals(result.getText()) && Math.abs(previous.bounds.centerX() - bounds.centerX()) < Math.min(previous.bounds.width(), bounds.width()) * .4f
                        && Math.abs(previous.bounds.centerY() - bounds.centerY()) < Math.min(previous.bounds.height(), bounds.height()) * .4f) duplicate = true;
            }
            if (!duplicate) codes.add(new Code(result.getText(), bounds));
        }
    }

    private static boolean supportedFrame(Bitmap screen) {
        return screen != null && !screen.isRecycled() && screen.getWidth() >= 540 && screen.getHeight() >= 1000
                && (long) screen.getWidth() * screen.getHeight() <= 5000000
                && Math.abs(screen.getHeight() / (double) screen.getWidth() - 2400.0 / 1080.0) < .035;
    }

    private static Rect confirmation(Context context, Bitmap screen) throws Exception {
        Bitmap reference;
        try (InputStream input = context.getAssets().open("mall_navigation/qr_confirmation_hint.png")) {
            reference = BitmapFactory.decodeStream(input);
        }
        if (reference == null) return null;
        Bitmap small = Bitmap.createScaledBitmap(screen, 540, 1200, true);
        try {
            if (difference(reference, small, 145, 910, true) > 12) return null;
            int left = 540, top = 1200, right = 0, bottom = 0, count = 0;
            for (int y = 440; y < 675; y++) for (int x = 170; x < 370; x++) {
                int pixel = small.getPixel(x, y), red = Color.red(pixel), green = Color.green(pixel), blue = Color.blue(pixel);
                if (green >= 110 && green >= red + 65 && green >= blue + 30) {
                    left = Math.min(left, x); top = Math.min(top, y); right = Math.max(right, x); bottom = Math.max(bottom, y); count++;
                }
            }
            if (count < 200 || right - left < 20 || right - left > 63 || bottom - top < 16 || bottom - top > 57) return null;
            return actual(new Rect(left, top, right + 1, bottom + 1), screen);
        } finally {
            reference.recycle();
            if (small != screen) small.recycle();
        }
    }

    private static double difference(Bitmap reference, Bitmap frame, int x, int y, boolean gray) {
        if (x < 0 || y < 0 || x + reference.getWidth() > frame.getWidth() || y + reference.getHeight() > frame.getHeight()) return Double.POSITIVE_INFINITY;
        double sum = 0;
        for (int j = 0; j < reference.getHeight(); j++) for (int i = 0; i < reference.getWidth(); i++) {
            int p = reference.getPixel(i, j), q = frame.getPixel(x + i, y + j);
            if (gray) sum += Math.abs(gray(p) - gray(q));
            else sum += (Math.abs(Color.red(p) - Color.red(q)) + Math.abs(Color.green(p) - Color.green(q)) + Math.abs(Color.blue(p) - Color.blue(q))) / 3.0;
        }
        return sum / (reference.getWidth() * reference.getHeight());
    }

    private static int gray(int pixel) { return (299 * Color.red(pixel) + 587 * Color.green(pixel) + 114 * Color.blue(pixel)) / 1000; }
    private static Rect actual(Rect box, Bitmap frame) {
        return new Rect(Math.round(box.left * frame.getWidth() / 540f), Math.round(box.top * frame.getHeight() / 1200f),
                Math.round(box.right * frame.getWidth() / 540f), Math.round(box.bottom * frame.getHeight() / 1200f));
    }

    private static void collect(AccessibilityNodeInfo node, List<Label> labels, Set<String> seen, int depth) {
        if (node == null || depth > 55 || labels.size() > 6000) return;
        Rect bounds = new Rect(); node.getBoundsInScreen(bounds);
        if (node.isVisibleToUser() && !bounds.isEmpty()) {
            CharSequence[] values = {node.getText(), node.getContentDescription()};
            for (CharSequence value : values) if (value != null && !value.toString().trim().isEmpty()) {
                String text = Normalizer.normalize(value.toString(), Normalizer.Form.NFKC).trim();
                if (seen.add(text + "|" + bounds.toShortString())) labels.add(new Label(text, bounds, node.isEnabled()));
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) { collect(child, labels, seen, depth + 1); child.recycle(); }
        }
    }

    private static Rect unique(List<Label> labels, Rect screen, float top, float bottom, String... names) {
        Rect found = null;
        for (Label label : labels) {
            if (!label.enabled || !Arrays.asList(names).contains(label.text) || label.bounds.top < screen.top + screen.height() * top
                    || label.bounds.bottom > screen.top + screen.height() * bottom) continue;
            if (found == null || found.contains(label.bounds)) found = new Rect(label.bounds);
            else if (!label.bounds.contains(found)) return null;
        }
        return found;
    }
    private static boolean has(List<Label> labels, String... names) {
        for (Label label : labels) if (Arrays.asList(names).contains(label.text)) return true;
        return false;
    }
    private static String exact(List<Label> labels, String expected) {
        if (expected.isEmpty()) return "";
        for (Label label : labels) if (key(label.text).equals(key(expected))) return label.text;
        return "";
    }
    private static boolean sameProductTitle(String nativeTitle, String sourceTitle) {
        if (sourceTitle.isEmpty()) return false;
        String source = key(sourceTitle), observed = key(nativeTitle);
        // The Mall accessibility title prefixes two verified brand-icon placeholders.
        // This exception applies only to the entire title, never to shop names or IDs.
        return observed.equals(source) || source.length() >= 12 && !source.startsWith("vv") && observed.equals("vv" + source);
    }
    private static String key(String value) { return Normalizer.normalize(value, Normalizer.Form.NFKC).replaceAll("\\s+", ""); }
    private static String first(JSONObject item, String... names) {
        for (String name : names) if (!item.optString(name).trim().isEmpty()) return item.optString(name).trim();
        return "";
    }
    private static Step step(String stage, String message) {
        Step out = new Step(); out.nextStage = stage; out.message = message; out.timeoutMessage = message + "超时，请检查手机后继续"; return out;
    }
    private static Step blocked(Step out, String message) { out.intervention = message; out.message = message; return out; }
    private static Step waiting(Step out, String message) { out.retry = true; out.message = message; return out; }
    private static Step actionOrImage(Step out, String message) { out.message = message; out.needsScreenshot = out.click == null; out.retry = out.click == null; return out; }
}
