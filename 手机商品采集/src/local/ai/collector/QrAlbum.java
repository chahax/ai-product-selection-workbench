package local.ai.collector;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.AtomicFile;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Own-image-only MediaStore staging, with a durable receipt before publication. */
public final class QrAlbum {
    private static final Object LOCK = new Object();
    private static final String ALBUM = Environment.DIRECTORY_PICTURES + "/AIProductCollectorQR/";
    private static final byte[] PNG_MAGIC = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};

    private QrAlbum() { }

    /** Keeps the complete verified URL, including every campaign/query parameter. */
    public static byte[] render(String payload) throws Exception {
        if (MallNavigator.productId(payload) == null) throw new IOException("二维码内容不是已核对的抖音商品完整链接");
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.H);
        hints.put(EncodeHintType.MARGIN, 4);
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        BitMatrix matrix = new QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 512, 512, hints);
        int[] pixels = new int[512 * 512];
        for (int y = 0; y < 512; y++) for (int x = 0; x < 512; x++) pixels[y * 512 + x] = matrix.get(x, y) ? 0xff000000 : 0xffffffff;
        Bitmap image = Bitmap.createBitmap(pixels, 512, 512, Bitmap.Config.ARGB_8888);
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            if (!image.compress(Bitmap.CompressFormat.PNG, 100, bytes)) throw new IOException("二维码 PNG 生成失败");
            return bytes.toByteArray();
        } finally { image.recycle(); }
    }

    /** Call on a worker thread. The returned receipt must be saved with the batch. */
    public static JSONObject save(Context context, JSONObject item, byte[] png) throws Exception {
        if (context == null || item == null || png == null || png.length < 8 || png.length > 8 * 1024 * 1024) {
            throw new IOException("二维码 PNG 缺失或超过大小限制");
        }
        for (int i = 0; i < PNG_MAGIC.length; i++) if (png[i] != PNG_MAGIC[i]) throw new IOException("二维码文件必须是 PNG");
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(png, 0, png.length, bounds);
        if (bounds.outWidth < 64 || bounds.outHeight < 64 || (long) bounds.outWidth * bounds.outHeight > 16000000) {
            throw new IOException("二维码图片尺寸无法安全解码");
        }
        Bitmap image = BitmapFactory.decodeByteArray(png, 0, png.length);
        if (image == null) throw new IOException("二维码 PNG 无法读取");
        String payload;
        try { payload = MallNavigator.qrPayload(image); }
        finally { image.recycle(); }
        String expectedId = item.optString("product_id");
        if (payload.isEmpty() || !expectedId.equals(MallNavigator.productId(payload))) {
            throw new IOException("二维码内商品 ID 与本件不一致，未写入手机相册");
        }
        synchronized (LOCK) {
            JSONObject receipt = new JSONObject();
            String token = UUID.randomUUID().toString();
            receipt.put("receipt_token", token);
            receipt.put("product_id", expectedId);
            receipt.put("display_name", "aipc-qr-" + expectedId + "-" + token + ".png");
            receipt.put("relative_path", ALBUM);
            receipt.put("owner_package", context.getPackageName());
            receipt.put("qr_payload", payload);
            receipt.put("png_sha256", sha256(png));
            receipt.put("created_at", Instant.now().toString());
            receipt.put("status", "allocating");
            writeReceipt(context, receipt);
            ContentResolver resolver = context.getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, receipt.getString("display_name"));
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            values.put(MediaStore.Images.Media.RELATIVE_PATH, ALBUM);
            values.put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis());
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
            Uri uri = resolver.insert(collection(), values);
            if (uri == null) throw new IOException("相册未返回本件二维码地址，保留了可恢复记录");
            receipt.put("uri", uri.toString());
            receipt.put("status", "writing");
            // The pre-insert display name also recovers a crash between insert and this write.
            writeReceipt(context, receipt);
            try (OutputStream output = resolver.openOutputStream(uri, "w")) {
                if (output == null) throw new IOException("无法写入本件二维码相册文件");
                output.write(png);
                output.flush();
            }
            ContentValues published = new ContentValues();
            published.put(MediaStore.Images.Media.IS_PENDING, 0);
            if (resolver.update(uri, published, null, null) != 1) throw new IOException("二维码相册发布未确认，保留待清理记录");
            receipt.put("status", "ready");
            writeReceipt(context, receipt);
            return new JSONObject(receipt.toString());
        }
    }

    /** Only returns after this app's exact image row is confirmed absent. Idempotent after success. */
    public static void remove(Context context, JSONObject suppliedReceipt) throws Exception {
        synchronized (LOCK) {
            String token = suppliedReceipt == null ? "" : suppliedReceipt.optString("receipt_token");
            JSONObject receipt = readReceipt(context, token);
            if (!context.getPackageName().equals(receipt.optString("owner_package")) || !ALBUM.equals(receipt.optString("relative_path"))) {
                throw new IOException("二维码清理回执不属于本应用");
            }
            String suppliedUri = suppliedReceipt.optString("uri");
            if (!suppliedUri.isEmpty() && !receipt.optString("uri").isEmpty() && !suppliedUri.equals(receipt.optString("uri"))) {
                throw new IOException("二维码清理地址与保存回执不一致");
            }
            if ("removed".equals(receipt.optString("status"))) return;
            Uri uri = resolveReceiptUri(context, receipt);
            if (uri != null) {
                assertOwnRow(context, receipt, uri);
                context.getContentResolver().delete(uri, null, null);
                if (rowPresent(context, uri)) throw new IOException("二维码删除尚未确认，将保留回执后重试");
            }
            receipt.put("status", "removed");
            receipt.put("removed_at", Instant.now().toString());
            writeReceipt(context, receipt);
        }
    }

    /** Recover these receipts before starting another item, including incomplete save() calls. */
    public static JSONArray pending(Context context) throws Exception {
        synchronized (LOCK) {
            JSONArray result = new JSONArray();
            File[] files = receiptDirectory(context).listFiles();
            if (files == null) throw new IOException("无法读取二维码待清理记录");
            Set<String> tokens = new HashSet<>();
            for (File file : files) {
                if (file.getName().matches("[0-9a-f-]{36}\\.json(?:\\.bak)?")) tokens.add(file.getName().substring(0, 36));
            }
            for (String token : tokens) {
                JSONObject receipt = readReceipt(context, token);
                if ("removed".equals(receipt.optString("status"))) continue;
                resolveReceiptUri(context, receipt);
                result.put(receipt);
            }
            return result;
        }
    }

    private static Uri resolveReceiptUri(Context context, JSONObject receipt) throws Exception {
        if (!receipt.optString("uri").isEmpty()) {
            Uri uri = ownUri(receipt.getString("uri"));
            return rowPresent(context, uri) ? uri : null;
        }
        String[] projection = {MediaStore.Images.Media._ID};
        String where = MediaStore.Images.Media.DISPLAY_NAME + "=? AND " + MediaStore.Images.Media.RELATIVE_PATH
                + "=? AND " + MediaStore.Images.Media.OWNER_PACKAGE_NAME + "=?";
        try (Cursor cursor = context.getContentResolver().query(collection(), projection, where,
                new String[]{receipt.getString("display_name"), ALBUM, context.getPackageName()}, null)) {
            if (cursor == null) throw new IOException("无法恢复本件二维码相册索引");
            if (!cursor.moveToFirst()) return null;
            Uri uri = ContentUris.withAppendedId(collection(), cursor.getLong(0));
            if (cursor.moveToNext()) throw new IOException("存在多个同名二维码记录，未自动删除");
            receipt.put("uri", uri.toString());
            writeReceipt(context, receipt);
            return uri;
        }
    }

    private static void assertOwnRow(Context context, JSONObject receipt, Uri uri) throws Exception {
        String[] projection = {MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.RELATIVE_PATH,
                MediaStore.Images.Media.OWNER_PACKAGE_NAME};
        try (Cursor cursor = context.getContentResolver().query(uri, projection, null, null, null)) {
            if (cursor == null) throw new IOException("相册没有返回二维码所有权信息");
            if (!cursor.moveToFirst()) return;
            if (!receipt.getString("display_name").equals(cursor.getString(0)) || !ALBUM.equals(cursor.getString(1))
                    || !context.getPackageName().equals(cursor.getString(2))) throw new IOException("二维码文件所有权或目录变化，未删除");
        }
    }

    private static boolean rowPresent(Context context, Uri uri) throws Exception {
        try (Cursor cursor = context.getContentResolver().query(uri, new String[]{MediaStore.Images.Media._ID}, null, null, null)) {
            if (cursor == null) throw new IOException("无法确认二维码相册状态");
            return cursor.moveToFirst();
        }
    }

    private static Uri collection() { return MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY); }
    private static Uri ownUri(String raw) throws IOException {
        Uri uri = Uri.parse(raw);
        if (!"content".equals(uri.getScheme()) || !"media".equals(uri.getAuthority())
                || uri.getPath() == null || !uri.getPath().matches("/(?:external_primary|external)/images/media/[0-9]+")
                || uri.getQuery() != null || uri.getFragment() != null) throw new IOException("二维码回执不是图片相册的单个文件地址");
        return uri;
    }

    private static File receiptDirectory(Context context) throws IOException {
        File directory = new File(context.getFilesDir(), "qr_album_receipts");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法保存二维码清理回执");
        return directory;
    }
    private static File receiptFile(Context context, String token) throws IOException {
        if (!token.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) throw new IOException("二维码回执编号不正确");
        return new File(receiptDirectory(context), token + ".json");
    }
    private static JSONObject readReceipt(Context context, String token) throws Exception {
        AtomicFile file = new AtomicFile(receiptFile(context, token));
        JSONObject receipt = new JSONObject(new String(file.readFully(), StandardCharsets.UTF_8));
        if (!token.equals(receipt.optString("receipt_token"))) throw new IOException("二维码本地回执编号不匹配");
        return receipt;
    }
    private static void writeReceipt(Context context, JSONObject receipt) throws Exception {
        AtomicFile file = new AtomicFile(receiptFile(context, receipt.getString("receipt_token")));
        FileOutputStream stream = file.startWrite();
        try {
            stream.write(receipt.toString().getBytes(StandardCharsets.UTF_8));
            file.finishWrite(stream);
        } catch (Exception error) {
            file.failWrite(stream);
            throw error;
        }
    }
    private static String sha256(byte[] data) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
        StringBuilder result = new StringBuilder();
        for (byte value : digest) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }
}
