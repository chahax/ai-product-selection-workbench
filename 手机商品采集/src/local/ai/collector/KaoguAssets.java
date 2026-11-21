package local.ai.collector;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.AtomicFile;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import javax.net.ssl.HttpsURLConnection;

/** Downloads only the verified detail API's cover image, without any account headers. */
public final class KaoguAssets {
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final long MAX_PIXELS = 24_000_000;
    private static final String[] IMAGE_HOSTS = {"ecombdimg.com", "byteimg.com", "ibyteimg.com", "douyinpic.com"};
    private KaoguAssets() { }

    /** Run on a worker thread. An image failure is explicit metadata and does not fabricate a file. */
    public static JSONObject mainImage(Context context, JSONObject item, String source) {
        JSONObject result = new JSONObject();
        put(result, "source_url", source == null ? "" : source);
        put(result, "source_field", "cover");
        try {
            if (source == null || source.isEmpty()) { put(result, "status", "not_provided"); return result; }
            if (source.length() > 12000) throw new IllegalArgumentException();
            URL current = new URL(source);
            if (!allowed(current)) { put(result, "status", "unsupported_host"); return result; }
            String batch = item.optString("batch_id"), product = item.optString("product_id");
            if (!batch.matches("[A-Za-z0-9_-]{1,100}") || !product.matches("[0-9]{10,25}")) throw new IllegalArgumentException();
            File base = new File(context.getFilesDir(), "kaogu_assets").getCanonicalFile();
            File folder = new File(base, batch + "-" + product).getCanonicalFile();
            if (!folder.getParentFile().equals(base)) throw new IllegalArgumentException();
            if (!folder.isDirectory() && !folder.mkdirs()) throw new IllegalStateException();
            AtomicFile metadata = new AtomicFile(new File(folder, "main-image.json"));
            JSONObject cached = cache(metadata, source, folder);
            if (cached != null) return cached;
            byte[] bytes = download(current);
            ImageInfo info = validate(bytes);
            File image = new File(folder, "main." + info.extension);
            write(new AtomicFile(image), bytes);
            put(result, "status", "saved");
            put(result, "file", image.getAbsolutePath());
            put(result, "mime", info.mime);
            put(result, "width", info.width);
            put(result, "height", info.height);
            put(result, "bytes", bytes.length);
            write(metadata, result.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception error) {
            put(result, "status", "download_failed");
            put(result, "error", "主图尚未保存，请稍后重试；商品原始主图链接已保留。");
        }
        return result;
    }

    private static JSONObject cache(AtomicFile metadata, String source, File folder) {
        try {
            JSONObject saved = new JSONObject(new String(metadata.readFully(), StandardCharsets.UTF_8));
            if (!"saved".equals(saved.optString("status")) || !source.equals(saved.optString("source_url"))) return null;
            File image = new File(saved.optString("file")).getCanonicalFile();
            if (!image.getParentFile().equals(folder) || !image.isFile() || image.length() < 16 || image.length() > MAX_BYTES) return null;
            try (InputStream in = new java.io.FileInputStream(image)) {
                ImageInfo info = validate(read(in));
                if (!info.mime.equals(saved.optString("mime")) || info.width != saved.optInt("width") || info.height != saved.optInt("height")) return null;
            }
            return saved;
        } catch (Exception absentOrInvalid) { return null; }
    }

    private static byte[] download(URL first) throws Exception {
        URL current = first;
        for (int redirect = 0; redirect < 4; redirect++) {
            if (!allowed(current)) throw new IllegalArgumentException();
            HttpsURLConnection connection = (HttpsURLConnection) current.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(15000);
            connection.setRequestProperty("Accept", "image/*");
            // Public media only: deliberately do not attach cookies, tokens, or web session headers.
            try {
                int status = connection.getResponseCode();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    String location = connection.getHeaderField("Location");
                    if (location == null || location.length() > 12000) throw new IllegalStateException();
                    current = new URL(current, location);
                    continue;
                }
                if (status != 200 || connection.getContentLengthLong() > MAX_BYTES) throw new IllegalStateException();
                try (InputStream in = connection.getInputStream()) { return read(in); }
            } finally { connection.disconnect(); }
        }
        throw new IllegalStateException();
    }

    private static boolean allowed(URL url) {
        if (!"https".equals(url.getProtocol()) || url.getUserInfo() != null || (url.getPort() != -1 && url.getPort() != 443)) return false;
        String host = url.getHost().toLowerCase(Locale.ROOT);
        for (String suffix : IMAGE_HOSTS) if (host.equals(suffix) || host.endsWith("." + suffix)) return true;
        return false;
    }

    private static ImageInfo validate(byte[] bytes) throws Exception {
        if (bytes.length < 16 || bytes.length > MAX_BYTES) throw new IllegalArgumentException();
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        if (bounds.outWidth < 1 || bounds.outHeight < 1 || (long) bounds.outWidth * bounds.outHeight > MAX_PIXELS) throw new IllegalArgumentException();
        String mime = bounds.outMimeType;
        String extension;
        if ("image/jpeg".equals(mime)) extension = "jpg";
        else if ("image/png".equals(mime)) extension = "png";
        else if ("image/webp".equals(mime)) extension = "webp";
        else if ("image/gif".equals(mime)) extension = "gif";
        else throw new IllegalArgumentException();
        BitmapFactory.Options sample = new BitmapFactory.Options();
        sample.inSampleSize = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / sample.inSampleSize > 1600) sample.inSampleSize *= 2;
        Bitmap image = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, sample);
        if (image == null) throw new IllegalArgumentException();
        image.recycle();
        return new ImageInfo(mime, extension, bounds.outWidth, bounds.outHeight);
    }

    private static byte[] read(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) != -1) { if (out.size() + n > MAX_BYTES) throw new IllegalStateException(); out.write(buffer, 0, n); }
        return out.toByteArray();
    }

    private static void write(AtomicFile file, byte[] bytes) throws Exception {
        FileOutputStream out = null;
        try { out = file.startWrite(); out.write(bytes); file.finishWrite(out); }
        catch (Exception error) { if (out != null) file.failWrite(out); throw error; }
    }
    private static void put(JSONObject target, String key, Object value) { try { target.put(key, value); } catch (Exception impossible) { throw new IllegalStateException(impossible); } }
    private static final class ImageInfo {
        final String mime, extension;
        final int width, height;
        ImageInfo(String mime, String extension, int width, int height) { this.mime = mime; this.extension = extension; this.width = width; this.height = height; }
    }
}
