package local.ai.collector;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONObject;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Pure native-UI decisions. The service owns timing, list aggregation and every action. */
public final class KaoguAppNavigator {
    private static final String KAOGU = KaoguAppAdapter.PACKAGE;
    private static final Set<String> TARGETS = new HashSet<>(Arrays.asList(
            "com.ss.android.ugc.aweme", "com.ss.android.ugc.livelite"));
    private static final Set<String> STAGES = new HashSet<>(Arrays.asList(
            "app_list", "date_open", "date_choose", "date_wait", "find_item",
            "app_detail", "open_price", "verify_destination"));

    public static final class Step {
        public Rect click;
        public boolean back;
        /** Native screen coordinates: start x/y and end x/y. */
        public int[] swipe;
        public String nextStage = "";
        public boolean ready;
        public String intervention = "";
        public String title = "";
        public String shop = "";
        public String targetPackage = "";
        public String message = "";
        public boolean retry;
        public int delayMs = 700;
        public int timeoutMs = 15000;
        public String timeoutMessage = "";
        /** Visible cards only. Root aggregates in source order, deduplicates and stops at N. */
        public List<KaoguAppAdapter.Card> cards = new ArrayList<>();
        public JSONObject metadata = new JSONObject();
    }

    private KaoguAppNavigator() { }

    /** No node references, gestures, process launches, generated IDs or mutable global progress. */
    public static Step next(AccessibilityNodeInfo root, KaoguAppAdapter.Snapshot screen, String stage, JSONObject item, String dateRange) {
        Step out = new Step();
        out.nextStage = stage;
        out.timeoutMessage = "考古加页面未按预期更新，请检查手机后继续";
        if (!STAGES.contains(stage)) return block(out, "无法识别考古加App导航阶段");
        String desiredDate = normalizeDateRange(dateRange);
        if (desiredDate.isEmpty()) return block(out, "日期参数只支持昨日、7日、30日");
        put(out.metadata, "source_app_package", KAOGU);
        put(out.metadata, "source_date_range", desiredDate);
        if (root == null || root.getPackageName() == null) return waitFor(out, "等待考古加App页面");
        String observedPackage = root.getPackageName().toString();
        if (stage.equals("verify_destination")) return verifyDestination(root, observedPackage, item, out);
        if (!KAOGU.equals(observedPackage) || screen == null) return block(out, "请回到考古加App的已筛选商品列表");
        if (!screen.intervention.isEmpty()) return block(out, screen.intervention);
        out.title = screen.title;
        out.shop = screen.shop;
        put(out.metadata, "observed_date_range", screen.selectedDate);
        if (screen.detail) put(out.metadata, "source_detail", copy(screen.metadata));
        if (screen.list && !screen.dateMenu && desiredDate.equals(screen.selectedDate)) out.cards = copyCards(screen.cards);
        String expectedTitle = first(item, "title", "source_title", "product_title", "name");

        if (stage.equals("app_list")) {
            if (screen.detail) return back(out, "app_list", "正在返回原筛选列表");
            if (screen.dateMenu) return advance(out, "date_choose", "日期菜单已打开，准备选择采集日期");
            if (!screen.list) return waitFor(out, "等待考古加已筛选商品列表，不会切换其他筛选项");
            return advance(out, desiredDate.equals(screen.selectedDate) ? "date_wait" : "date_open",
                    "正在确认本次采集日期");
        }
        if (stage.equals("date_open")) {
            if (screen.dateMenu) return advance(out, "date_choose", "正在读取日期菜单");
            if (!screen.list) return waitFor(out, "等待商品列表的日期入口");
            if (desiredDate.equals(screen.selectedDate)) return advance(out, "date_wait", "当前日期已与参数一致");
            if (screen.dateControl == null) return waitFor(out, "未定位到日期入口，未修改其他筛选项");
            return click(out, screen.dateControl, "date_choose", "正在打开日期选择");
        }
        if (stage.equals("date_choose")) {
            if (!screen.dateMenu) return waitFor(out, "等待昨日、7日、30日日期菜单");
            Rect option = screen.dateOptions.get(desiredDate);
            if (option == null) return block(out, "日期菜单没有本次参数对应的选项");
            return click(out, option, "date_wait", "正在选择“" + desiredDate + "”");
        }
        if (stage.equals("date_wait")) {
            out.timeoutMs = 20000;
            out.timeoutMessage = "未确认日期已切换完成，未开始读取商品名单";
            if (screen.dateMenu || !screen.list || !desiredDate.equals(screen.selectedDate)) {
                return waitFor(out, "等待日期标签和筛选商品列表更新完成");
            }
            return advance(out, "find_item", "日期已核对，正在读取当前筛选结果");
        }
        if (stage.equals("find_item")) {
            if (screen.detail) return back(out, "find_item", "正在回到原筛选列表查找本件");
            if (!screen.list || screen.dateMenu) return waitFor(out, "等待已筛选商品列表");
            if (!desiredDate.equals(screen.selectedDate)) return block(out, "商品列表日期发生变化，未继续采集");
            if (expectedTitle.isEmpty()) {
                // Empty item means discovery mode. Each result carries the native
                // order currently visible; only root may assign a source_item_key.
                out.swipe = listSwipe(root, screen.cards);
                out.retry = true;
                out.message = screen.cards.isEmpty() ? "当前未读取到完整商品卡片" : "已读取可见商品，正在向下补充名单";
                out.timeoutMessage = "未读取到更多商品，保留已读取名单等待核对";
                return out;
            }
            List<KaoguAppAdapter.Card> matches = new ArrayList<>();
            for (KaoguAppAdapter.Card card : screen.cards) {
                if (!sameSourceTitle(card.title, expectedTitle)) continue;
                matches.add(card);
            }
            if (matches.size() > 1) {
                JSONObject savedCard = item.optJSONObject("source_card");
                List<KaoguAppAdapter.Card> exactCards = new ArrayList<>();
                for (KaoguAppAdapter.Card card : matches) {
                    if (sameCardMetrics(savedCard, card.metadata)) exactCards.add(card);
                }
                if (exactCards.size() != 1) return block(out, "列表里有多个同标题商品，未能唯一核对本件卡片");
                matches = exactCards;
            }
            if (matches.size() == 1) {
                KaoguAppAdapter.Card card = matches.get(0);
                out.title = card.title;
                out.shop = card.metadata == null ? "" : first(card.metadata, "shop_name", "shop");
                put(out.metadata, "source_card", copy(card.metadata));
                return click(out, card.titleBounds, "app_detail", "正在打开本件考古加商品详情");
            }
            out.swipe = listSwipe(root, screen.cards);
            out.retry = true;
            out.message = "本件不在当前可见列表，正在继续向下查找";
            out.timeoutMessage = "未在当前筛选列表中核对到本件，未打开相似商品";
            return out;
        }
        if (expectedTitle.isEmpty()) return block(out, "本件名单缺少完整标题，未打开查看价格");
        if (!screen.detail) return waitFor(out, "等待本件考古加详情页");
        if (screen.title.isEmpty()) return waitFor(out, "等待商品完整标题加载");
        if (!sameSourceTitle(screen.title, expectedTitle)) return block(out, "考古加详情标题与名单不一致，未点击查看价格");
        if (stage.equals("app_detail")) {
            // A second, fresh snapshot in open_price must confirm the same full
            // source title before any external jump is allowed.
            out.delayMs = 700;
            return advance(out, "open_price", "已读取本件完整标题，等待下一帧确认稳定");
        }
        if (stage.equals("open_price")) {
            if (screen.viewPrice == null) return waitFor(out, "等待右上角查看价格入口");
            put(out.metadata, "source_title", screen.title);
            put(out.metadata, "source_shop", screen.shop);
            return click(out, screen.viewPrice, "verify_destination", "正在通过考古加查看价格打开商品");
        }
        return block(out, "考古加导航状态需要重新确认");
    }

    private static boolean sameCardMetrics(JSONObject saved, JSONObject live) {
        if (saved == null || live == null
                || !saved.optString("commission_rate_text").equals(live.optString("commission_rate_text"))) return false;
        JSONObject expected = saved.optJSONObject("metrics");
        JSONObject actual = live.optJSONObject("metrics");
        if (expected == null || actual == null || expected.length() < 2 || expected.length() != actual.length()) return false;
        for (java.util.Iterator<String> keys = expected.keys(); keys.hasNext();) {
            String name = keys.next();
            if (!actual.has(name) || !expected.optString(name).equals(actual.optString(name))) return false;
        }
        return true;
    }

    private static Step verifyDestination(AccessibilityNodeInfo root, String observedPackage, JSONObject item, Step out) {
        out.timeoutMs = 26000;
        out.timeoutMessage = "查看价格后未读取到抖音商品详情，未开始采集";
        if (KAOGU.equals(observedPackage)) return waitFor(out, "等待考古加查看价格跳转到抖音商品详情");
        if (!TARGETS.contains(observedPackage)) return block(out, "查看价格跳转被其他应用或系统窗口遮挡，请检查手机");
        out.targetPackage = observedPackage;
        String expected = first(item, "title", "source_title", "product_title", "name");
        DouyinAdapter.Snapshot target = DouyinAdapter.read(root);
        if (!target.intervention.isEmpty()) return block(out, target.intervention);
        if (target.popup || target.gallery) return block(out, "请先保持抖音商品详情页打开");
        out.title = target.title;
        if (target.title.isEmpty()) return waitFor(out, "等待目标商品完整标题加载");
        out.ready = true;
        out.message = "已打开抖音商品页，正在采集";
        put(out.metadata, "identity_basis", "kaogu_app_view_price");
        put(out.metadata, "identity_title", target.title);
        put(out.metadata, "target_title", target.title);
        put(out.metadata, "source_title", expected);
        put(out.metadata, "target_package", observedPackage);
        put(out.metadata, "product_page_evidence", target.openSpecs != null && !target.openSpecs.isEmpty()
                ? "native_spec_entry_and_title" : "native_product_title");
        // Native source_item_key is assigned by root. Never turn an ordinal,
        // title hash, promotion label or price into an invented product ID.
        put(out.metadata, "product_id", item == null ? "" : item.optString("product_id"));
        if (item != null) put(out.metadata, "source_item_key", item.optString("source_item_key"));
        return out;
    }

    public static String normalizeDateRange(String value) {
        String raw = value == null ? "" : key(value);
        if (Arrays.asList("昨日", "昨天", "yesterday", "1d").contains(raw)) return "昨日";
        if (Arrays.asList("7日", "7天", "近7日", "近7天", "7d").contains(raw)) return "7日";
        if (Arrays.asList("30日", "30天", "近30日", "近30天", "30d").contains(raw)) return "30日";
        return "";
    }

    private static boolean sameSourceTitle(String observed, String expected) {
        return !expected.isEmpty() && key(observed).equals(key(expected));
    }
    private static int[] listSwipe(AccessibilityNodeInfo root, List<KaoguAppAdapter.Card> cards) {
        if (cards.isEmpty()) return null;
        Rect screen = new Rect(); root.getBoundsInScreen(screen);
        int top = screen.bottom, bottom = screen.top;
        for (KaoguAppAdapter.Card card : cards) {
            if (card.bounds == null || card.bounds.isEmpty()) continue;
            top = Math.min(top, card.bounds.top);
            bottom = Math.max(bottom, card.bounds.bottom);
        }
        top = Math.max(top, screen.top + Math.round(screen.height() * .20f));
        bottom = Math.min(bottom, screen.top + Math.round(screen.height() * .88f));
        if (bottom - top < screen.height() * .12f) return null;
        int x = screen.centerX(), distance = bottom - top;
        return new int[]{x, top + distance * 4 / 5, x, top + distance / 5};
    }
    private static List<KaoguAppAdapter.Card> copyCards(List<KaoguAppAdapter.Card> cards) {
        List<KaoguAppAdapter.Card> result = new ArrayList<>();
        for (KaoguAppAdapter.Card original : cards) {
            if (original.title.isEmpty() || original.bounds == null || original.bounds.isEmpty()) continue;
            KaoguAppAdapter.Card card = new KaoguAppAdapter.Card();
            card.title = original.title;
            card.bounds = new Rect(original.bounds);
            card.titleBounds = original.titleBounds == null ? new Rect() : new Rect(original.titleBounds);
            card.metadata = copy(original.metadata);
            result.add(card);
        }
        return result;
    }
    private static JSONObject copy(JSONObject value) {
        try { return value == null ? new JSONObject() : new JSONObject(value.toString()); }
        catch (Exception invalid) { throw new IllegalStateException("无法复制考古加商品元数据", invalid); }
    }
    private static String first(JSONObject item, String... names) {
        if (item == null) return "";
        for (String name : names) {
            String value = item.optString(name).trim();
            if (!value.isEmpty() && !value.equals("null")) return value;
        }
        return "";
    }
    private static String key(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC).replaceAll("\\s+", "");
    }
    private static void put(JSONObject object, String name, Object value) {
        try { object.put(name, value); }
        catch (Exception invalid) { throw new IllegalStateException("无法复制考古加导航字段", invalid); }
    }
    private static Step block(Step out, String message) { out.intervention = message; out.message = message; return out; }
    private static Step waitFor(Step out, String message) { out.retry = true; out.message = message; return out; }
    private static Step advance(Step out, String nextStage, String message) { out.nextStage = nextStage; return waitFor(out, message); }
    private static Step back(Step out, String nextStage, String message) { out.back = true; out.nextStage = nextStage; out.message = message; return out; }
    private static Step click(Step out, Rect bounds, String nextStage, String message) {
        if (bounds == null || bounds.isEmpty()) return block(out, "操作入口没有可核对的原生位置");
        out.click = new Rect(bounds); out.nextStage = nextStage; out.message = message; return out;
    }
}
