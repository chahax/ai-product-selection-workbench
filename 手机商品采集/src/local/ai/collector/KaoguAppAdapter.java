package local.ai.collector;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONObject;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Copies only visible native accessibility evidence from the official Kaogu App. */
public final class KaoguAppAdapter {
    public static final String PACKAGE = "com.fz.kaogujia";
    private static final Set<String> DATES = new HashSet<>(Arrays.asList("昨日", "7日", "30日"));
    private static final Set<String> CORE_PERIODS = new HashSet<>(Arrays.asList("7日", "30日", "90日", "180日", "365日", "730日", "自定义"));
    private static final String[] CORE_LABELS = {"商品销量", "直播销量", "视频销量", "商品卡销量"};
    private static final Pattern DATA_DATES = Pattern.compile("(?<![0-9])([0-9]{4})\\.([0-9]{1,2})\\.([0-9]{1,2})\\s*-\\s*([0-9]{4})\\.([0-9]{1,2})\\.([0-9]{1,2})(?![0-9])");
    private static final int MAX_NODES = 3500;
    static final String PERSONAL_CARE = "个护健康电器";
    static final String PERSONAL_CARE_PATH = "3C数码家电-电器-个护健康电器";

    public static final class Snapshot {
        public boolean list;
        public boolean detail;
        public boolean dateMenu;
        public boolean productPreview;
        public Rect previewDetails;
        public String selectedDate = "";
        public String title = "";
        public String shop = "";
        public Rect dateControl;
        public Rect productTab;
        public Rect productLibraryTab;
        public String category = "";
        public String categoryPath = "";
        public Rect categoryControl;
        public Rect categoryFinish;
        public Rect categorySearch;
        public Rect categorySearchField;
        public Map<String, Rect> categorySearchResults = new LinkedHashMap<>();
        public Rect personalCareOption;
        public Rect viewPrice;
        public Rect back;
        public List<Card> cards = new ArrayList<>();
        public Map<String, Rect> dateOptions = new LinkedHashMap<>();
        public Map<String, Rect> coreDateOptions = new LinkedHashMap<>();
        public JSONObject metadata = new JSONObject();
        public String intervention = "";
    }

    public static final class Card {
        public String title = "";
        public Rect bounds = new Rect();
        public Rect titleBounds = new Rect();
        public JSONObject metadata = new JSONObject();
    }

    private static final class Node {
        String text = "", description = "", id = "", className = "", packageName = "";
        Rect bounds = new Rect(), action;
        boolean clickable, enabled, selected;
        Node parent;
        final List<Node> children = new ArrayList<>();
    }

    private KaoguAppAdapter() { }

    public static Snapshot read(AccessibilityNodeInfo root) { return read(root, ""); }

    /** Activity names may be supplied from the service's observed native window event. */
    public static Snapshot read(AccessibilityNodeInfo root, String activityClass) {
        Snapshot result = new Snapshot();
        if (root == null) return result;
        List<Node> nodes = new ArrayList<>();
        collect(root, null, null, nodes, 0);
        if (nodes.isEmpty() || !PACKAGE.equals(nodes.get(0).packageName)) return result;
        return parse(nodes, activityClass);
    }

    private static Snapshot parse(List<Node> nodes, String activityClass) {
        Snapshot result = new Snapshot();
        String ordering = "";
        Node productList = null;
        Node price = null, detailHeader = null;
        for (Node node : nodes) {
            if (!PACKAGE.equals(node.packageName) || !node.enabled || node.bounds.isEmpty()) continue;
            if (id(node, "txv_name_item") && DATES.contains(node.text)) {
                result.dateOptions.put(node.text, action(node));
                if (node.selected) result.selectedDate = node.text;
            }
            if (id(node, "txv_day") && DATES.contains(node.text)) {
                result.dateControl = action(node);
                result.selectedDate = node.text;
            }
            if (id(node, "txv_shop_type")) {
                result.category = node.text;
                result.categoryControl = new Rect(node.bounds);
            }
            if (id(node, "txv_shop") && "商品".equals(node.text)) result.productTab = new Rect(node.bounds);
            if (id(node, "txv_shop") && "商品库".equals(node.text)) result.productLibraryTab = new Rect(node.bounds);
            if (id(node, "txv_select_content")) result.categoryPath = node.text;
            if (id(node, "btn_finish") && "完成".equals(node.text)) result.categoryFinish = new Rect(node.bounds);
            if (id(node, "et_search") && node.bounds.top > nodes.get(0).bounds.height() / 3) {
                result.categorySearchField = new Rect(node.bounds);
            }
            if (id(node, "txv_search") && "搜索".equals(node.text)
                    && node.bounds.top > nodes.get(0).bounds.height() / 3) result.categorySearch = new Rect(node.bounds);
            if (id(node, "txv_search")) {
                String prefix = "3C数码家电/电器/个护健康电器";
                if (node.text.equals(prefix)) result.categorySearchResults.put(PERSONAL_CARE_PATH, new Rect(node.bounds));
                else if (node.text.startsWith(prefix + "/")) {
                    String child = node.text.substring(prefix.length() + 1);
                    result.categorySearchResults.put(PERSONAL_CARE_PATH + "-" + child, new Rect(node.bounds));
                }
            }
            if (id(node, "txv_name_item") && PERSONAL_CARE.equals(node.text)) result.personalCareOption = new Rect(node.bounds);
            if (id(node, "txv_sort")) ordering = node.text;
            if (id(node, "rec_daren") && ancestor(node, "vp_shop_root") != null) productList = node;
            if (id(node, "btn_to_douyin") && node.text.equals("查看价格")) price = node;
            if (id(node, "in_top_big")) detailHeader = node;
            if (id(node, "imv_back") && node.clickable) result.back = action(node);
            if ("查看数据详情".equals(node.text) && node.action != null) {
                result.productPreview = true;
                result.previewDetails = action(node);
            }
            String warning = intervention(node.text);
            if (result.intervention.isEmpty() && !warning.isEmpty()) result.intervention = warning;
        }
        result.dateMenu = result.dateOptions.keySet().containsAll(DATES);
        if (result.productPreview) return result;
        if (!result.dateMenu && result.categoryFinish == null && result.categorySearchField == null
                && productList != null && result.dateControl != null) {
            result.list = true;
            for (Node container : productList.children) {
                if (!id(container, "lin_root") || !container.clickable || !container.enabled) continue;
                Node title = descendant(container, "txv_name");
                // A bottom-cropped row in the observed XML has a title but no metrics and is
                // overlapped by the fixed navigation bar. Wait until scrolling exposes that row.
                if (title == null || title.text.isEmpty() || title.bounds.isEmpty() || descendant(container, "txv_value1") == null) continue;
                Card card = new Card();
                card.title = title.text;
                card.bounds = action(container);
                card.titleBounds = new Rect(title.bounds);
                put(card.metadata, "source", "kaogujia_app_accessibility");
                put(card.metadata, "title", title.text);
                put(card.metadata, "shop_name", "");
                put(card.metadata, "date_label", result.selectedDate);
                put(card.metadata, "category_text", result.category);
                put(card.metadata, "sort_text", ordering);
                Node commission = descendant(container, "txv_commission");
                if (commission != null) put(card.metadata, "commission_rate_text", commission.text);
                JSONObject metrics = new JSONObject();
                for (int n = 1; n <= 4; n++) {
                    Node metricName = descendant(container, "txv_value_name" + n);
                    Node metricValue = descendant(container, "txv_value" + n);
                    if (metricName != null && metricValue != null && !metricName.text.isEmpty()) put(metrics, metricName.text, metricValue.text);
                }
                put(card.metadata, "metrics", metrics);
                Node image = descendant(container, "imv_avatar");
                if (image != null) put(card.metadata, "main_image_bounds", rect(image.bounds));
                result.cards.add(card);
            }
        }
        if (!result.dateMenu && price != null && detailHeader != null) {
            result.detail = true;
            result.list = false;
            result.cards.clear();
            result.viewPrice = action(price);
            Node title = descendant(detailHeader, "txv_name"), shop = descendant(detailHeader, "txv_store_name");
            if (title != null) result.title = title.text;
            if (shop != null) result.shop = shop.text;
            put(result.metadata, "source", "kaogujia_app_accessibility");
            put(result.metadata, "title", result.title);
            put(result.metadata, "shop_name", result.shop);
            copyText(detailHeader, "txv_brande_name", result.metadata, "brand_name");
            copyText(detailHeader, "txv_public_commission", result.metadata, "commission_rate_text");
            copyText(detailHeader, "txv_like", result.metadata, "good_ratio_text");
            copyText(detailHeader, "txv_pulish_time", result.metadata, "publish_time_text");
            copyText(detailHeader, "txv_type", result.metadata, "category_text");
            JSONObject metrics = new JSONObject();
            for (int n = 1; n <= 3; n++) {
                Node label = descendant(detailHeader, "txv_value_name" + n), value = descendant(detailHeader, "txv_value" + n);
                if (label != null && value != null && !label.text.isEmpty()) put(metrics, label.text, value.text);
            }
            put(result.metadata, "header_metrics", metrics);
            Node image = descendant(detailHeader, "imv_avatar");
            if (image != null) put(result.metadata, "main_image_bounds", rect(image.bounds));
            for (Node node : nodes) if (id(node, "txv_time")) put(result.metadata, "data_time_text", node.text);
        }
        readCoreData(nodes, result);
        return result;
    }

    private static void readCoreData(List<Node> nodes, Snapshot result) {
        Node core = null;
        for (Node node : nodes) {
            if (id(node, "rel_data") && ancestor(node, "frg_data") != null) { core = node; break; }
        }
        if (core == null) return;
        JSONObject values = new JSONObject();
        for (int i = 0; i < CORE_LABELS.length; i++) {
            Node column = null;
            for (Node child : core.children) if (id(child, "lin_value" + (i + 1))) { column = child; break; }
            if (column == null) continue;
            boolean named = false;
            for (Node child : column.children) if (child.text.equals(CORE_LABELS[i])) named = true;
            Node value = descendant(column, "txv_value" + (i + 1));
            if (named && value != null && presentMetric(value.text)) put(values, CORE_LABELS[i], value.text);
        }
        put(result.metadata, "core_data", values);
        put(result.metadata, "core_data_complete", values.length() == CORE_LABELS.length);
        Node section = ancestor(core, "frg_data");
        Node date = descendant(section, "txv_time");
        if (date != null && !date.text.isEmpty()) put(result.metadata, "core_data_date_text", date.text);
        int observedDays = date == null ? 0 : inclusiveDays(date.text);
        if (observedDays > 0) put(result.metadata, "core_data_period_days", observedDays);
        Node periods = descendant(section, "date_select");
        Set<String> selected = new HashSet<>();
        if (periods != null) for (Node option : periods.children) {
            if (!option.enabled || option.bounds.isEmpty() || !CORE_PERIODS.contains(option.text)) continue;
            if (option.clickable) result.coreDateOptions.put(option.text, new Rect(option.bounds));
            if (option.selected) selected.add(option.text);
        }
        // Some versions expose every option as selected=false. The actual displayed
        // date range can establish its inclusive duration without using today's date.
        String period = selected.size() == 1 ? selected.iterator().next() : observedDays > 0 ? observedDays + "日" : "";
        put(result.metadata, "core_data_period", period);
        put(result.metadata, "core_data_period_source", selected.size() == 1 ? "native_selected"
                : observedDays > 0 ? "data_date_range_inclusive" : "selection_not_exposed");
    }

    private static int inclusiveDays(String text) {
        try {
            Matcher dates = DATA_DATES.matcher(text);
            if (!dates.find()) return 0;
            LocalDate first = LocalDate.of(Integer.parseInt(dates.group(1)), Integer.parseInt(dates.group(2)), Integer.parseInt(dates.group(3)));
            LocalDate last = LocalDate.of(Integer.parseInt(dates.group(4)), Integer.parseInt(dates.group(5)), Integer.parseInt(dates.group(6)));
            long days = ChronoUnit.DAYS.between(first, last) + 1;
            return days > 0 && days <= 36600 ? (int) days : 0;
        } catch (Exception invalidDate) { return 0; }
    }

    private static boolean presentMetric(String text) {
        return !text.isEmpty() && !Arrays.asList("-", "--", "—", "暂无", "暂无数据", "加载中", "...", "…").contains(text);
    }

    private static Node collect(AccessibilityNodeInfo nativeNode, Node parent, Rect inheritedAction, List<Node> nodes, int depth) {
        if (depth > 65 || nodes.size() >= MAX_NODES || !nativeNode.isVisibleToUser()) return null;
        Node node = new Node();
        node.parent = parent;
        node.text = text(nativeNode.getText());
        node.description = text(nativeNode.getContentDescription());
        node.id = text(nativeNode.getViewIdResourceName());
        node.className = text(nativeNode.getClassName());
        node.packageName = text(nativeNode.getPackageName());
        node.enabled = nativeNode.isEnabled();
        node.clickable = nativeNode.isClickable();
        node.selected = nativeNode.isSelected() || nativeNode.isChecked();
        nativeNode.getBoundsInScreen(node.bounds);
        node.action = node.clickable && node.enabled && !node.bounds.isEmpty() ? new Rect(node.bounds)
                : inheritedAction == null ? null : new Rect(inheritedAction);
        nodes.add(node);
        if (parent != null) parent.children.add(node);
        int count = Math.min(nativeNode.getChildCount(), MAX_NODES - nodes.size());
        for (int i = 0; i < count && nodes.size() < MAX_NODES; i++) {
            AccessibilityNodeInfo child = nativeNode.getChild(i);
            if (child != null) {
                try { collect(child, node, node.action, nodes, depth + 1); }
                finally { child.recycle(); }
            }
        }
        return node;
    }

    private static boolean id(Node node, String suffix) { return (PACKAGE + ":id/" + suffix).equals(node.id); }
    private static Node ancestor(Node node, String suffix) {
        for (Node p = node.parent; p != null; p = p.parent) if (id(p, suffix)) return p;
        return null;
    }
    private static Node descendant(Node node, String suffix) {
        if (id(node, suffix)) return node;
        for (Node child : node.children) { Node found = descendant(child, suffix); if (found != null) return found; }
        return null;
    }
    private static void copyText(Node parent, String id, JSONObject target, String key) {
        Node node = descendant(parent, id);
        if (node != null && !node.text.isEmpty()) put(target, key, node.text);
    }
    private static JSONObject rect(Rect bounds) {
        JSONObject value = new JSONObject();
        put(value, "left", bounds.left); put(value, "top", bounds.top); put(value, "right", bounds.right); put(value, "bottom", bounds.bottom);
        return value;
    }
    private static void put(JSONObject target, String key, Object value) {
        try { target.put(key, value); } catch (Exception error) { throw new IllegalStateException(error); }
    }
    private static Rect action(Node node) { return node.action == null ? new Rect(node.bounds) : new Rect(node.action); }
    private static String text(CharSequence value) { return value == null ? "" : value.toString().trim().replaceAll("\\s+", " "); }
    private static String intervention(String value) {
        if (value.equals("网络异常") || value.equals("加载失败") || value.equals("无网络连接") || value.contains("网络异常，请")) return "考古加网络异常，请检查连接后重试";
        if (value.contains("请先登录") || value.contains("登录后查看") || value.contains("登录已失效") || value.contains("账号已过期")) return "请在考古加App内完成登录";
        if (value.contains("请完成安全验证") || value.contains("请输入验证码")) return "请本人完成考古加验证";
        return "";
    }
}
