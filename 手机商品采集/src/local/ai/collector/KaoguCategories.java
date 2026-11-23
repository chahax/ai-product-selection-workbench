package local.ai.collector;

import android.content.Context;
import android.util.AtomicFile;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class KaoguCategories {
    private static final String ASSET_NAME = "kaogu-categories.json";
    private static final String CACHE_NAME = "kaogu-categories-cache.json";
    private static final int MAX_DEPTH = 5;
    private static final int MAX_PATH_LENGTH = 100;
    private static final int MAX_CATEGORIES = 50_000;
    static final String ALL = "不限";

    static final class Category {
        final String name;
        final List<Category> children;

        Category(String name, List<Category> children) {
            this.name = name;
            this.children = Collections.unmodifiableList(children);
        }
    }

    private static final class Snapshot {
        final String version;
        final List<Category> tree;
        final Map<String, List<String>> paths;

        Snapshot(String version, JSONArray categories) throws JSONException {
            this.version = version;
            Map<String, List<String>> entries = new LinkedHashMap<>();
            tree = read(categories, Collections.emptyList(), entries);
            if (entries.isEmpty()) throw new JSONException("考古加类目列表为空");
            paths = Collections.unmodifiableMap(entries);
        }
    }

    private static Snapshot current;
    private static Snapshot bundled;

    private KaoguCategories() { }

    static List<Category> tree(Context context) { return snapshot(context).tree; }
    static String version(Context context) { return snapshot(context).version; }

    private static synchronized Snapshot snapshot(Context context) {
        if (current != null) return current;
        try (InputStream input = cache(context).openRead()) {
            current = decode(new JSONObject(readText(input)));
        } catch (FileNotFoundException absent) {
            current = bundled(context);
        } catch (IOException | JSONException invalid) {
            android.util.Log.d("PhoneCollector", "考古加类目缓存无法读取，使用随 App 安装的类目表");
            current = bundled(context);
        }
        return current;
    }

    private static synchronized Snapshot bundled(Context context) {
        if (bundled != null) return bundled;
        try (InputStream input = context.getAssets().open(ASSET_NAME)) {
            bundled = new Snapshot("", new JSONArray(readText(input)));
            return bundled;
        } catch (IOException | JSONException error) {
            throw new IllegalStateException("考古加类目表无法读取", error);
        }
    }

    static void update(Context context, JSONObject response) throws IOException, JSONException {
        if (Boolean.TRUE.equals(response.opt("unchanged"))) return;
        Snapshot next = decode(response);
        JSONObject saved = new JSONObject().put("version", next.version).put("total", next.paths.size())
                .put("categories", response.getJSONArray("categories"));
        byte[] bytes = saved.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > WorkbenchConnection.MAX_CATEGORIES_RESPONSE_BYTES) throw new IOException("类目列表过大");

        synchronized (KaoguCategories.class) {
            if (next.version.equals(snapshot(context).version)) return;
            AtomicFile file = cache(context);
            FileOutputStream output = file.startWrite();
            try {
                output.write(bytes);
                file.finishWrite(output);
            } catch (IOException | RuntimeException error) {
                file.failWrite(output);
                throw error;
            }
            current = next;
        }
    }

    private static Snapshot decode(JSONObject response) throws JSONException {
        Object rawVersion = response.opt("version");
        if (!(rawVersion instanceof String) || !((String) rawVersion).matches("[a-f0-9]{64}")) {
            throw new JSONException("考古加类目版本无效");
        }
        Snapshot next = new Snapshot((String) rawVersion, response.getJSONArray("categories"));
        Object total = response.opt("total");
        if (!(total instanceof Number) || ((Number) total).doubleValue() != next.paths.size()) {
            throw new JSONException("考古加类目数量与列表不一致");
        }
        return next;
    }

    private static AtomicFile cache(Context context) {
        return new AtomicFile(new File(context.getFilesDir(), CACHE_NAME));
    }

    private static String readText(InputStream input) throws IOException {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        for (int count; (count = input.read(chunk)) != -1;) {
            if (data.size() + count > WorkbenchConnection.MAX_CATEGORIES_RESPONSE_BYTES) throw new IOException("类目列表过大");
            data.write(chunk, 0, count);
        }
        return new String(data.toByteArray(), StandardCharsets.UTF_8);
    }

    private static List<Category> read(JSONArray array, List<String> parents, Map<String, List<String>> entries)
            throws JSONException {
        if (parents.size() >= MAX_DEPTH && array.length() > 0) throw new JSONException("考古加类目层级无效");
        List<Category> nodes = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.getJSONObject(i);
            Object value = item.opt("name");
            if (!(value instanceof String)) throw new JSONException("考古加类目名称无效");
            String name = (String) value;
            if (name.isEmpty() || !name.equals(name.trim()) || ALL.equals(name)) throw new JSONException("考古加类目名称无效");
            for (int j = 0; j < name.length(); j++) {
                if (Character.isISOControl(name.charAt(j))) throw new JSONException("考古加类目名称无效");
            }
            List<String> path = new ArrayList<>(parents);
            path.add(name);
            String key = String.join("-", path);
            if (key.length() > MAX_PATH_LENGTH || entries.size() >= MAX_CATEGORIES || entries.containsKey(key)) {
                throw new JSONException("考古加类目路径无效");
            }
            entries.put(key, Collections.unmodifiableList(path));
            JSONArray children = item.has("children") ? item.getJSONArray("children") : null;
            nodes.add(new Category(name, children == null ? Collections.emptyList() : read(children, path, entries)));
        }
        return Collections.unmodifiableList(nodes);
    }

    static List<String> path(Context context, String encoded) {
        if (encoded == null || encoded.isEmpty() || ALL.equals(encoded)) return Collections.emptyList();
        Snapshot current = snapshot(context);
        List<String> names = current.paths.get(encoded);
        if (names != null) return names;
        // A removed path can still be needed by a saved job or manually configured batch.
        if (!current.version.isEmpty()) {
            names = bundled(context).paths.get(encoded);
            if (names != null) return names;
        }
        String prefix = KaoguAppAdapter.PERSONAL_CARE_PATH + "-";
        // Keep earlier manually entered settings and saved tasks readable.
        if (encoded.startsWith(prefix) && encoded.length() > prefix.length()) {
            return Arrays.asList("3C数码家电", "电器", KaoguAppAdapter.PERSONAL_CARE, encoded.substring(prefix.length()));
        }
        return Collections.emptyList();
    }
}
