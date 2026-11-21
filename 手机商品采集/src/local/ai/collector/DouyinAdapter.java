package local.ai.collector;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONException;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A copied, read-only view of the currently accessible Douyin Mall screen. */
public final class DouyinAdapter {
    private static final String VALUE = "[0-9]+(?:,[0-9]{3})*(?:\\.[0-9]{1,2})?";
    private static final String BEFORE = "展示价|页面价|优惠前|券前价|原价|日常价|大促价|售价";
    private static final String AFTER = "火爆价|预估券后价|预估到手价|百亿补贴价|平台补贴价|券后价|实付价|补贴价|到手价|活动价|优惠价|限时价|拼团价|秒杀价|首单价|首件价|会员价|新人价";
    private static final Pattern PAIR = Pattern.compile("^(" + BEFORE + "|火爆价)?¥(" + VALUE + ")(" + AFTER + ")¥(" + VALUE + ")$");
    private static final Pattern PAID_FIRST = Pattern.compile("^实付价¥(" + VALUE + ")(" + BEFORE + ")¥(" + VALUE + ")$");
    private static final Pattern SINGLE = Pattern.compile("^(" + AFTER + "|" + BEFORE + ")?¥(" + VALUE + ")$");
    private static final Pattern SAVINGS = Pattern.compile("^(?:加补\\+平台券等共省|券共减|已优惠|共省)¥?(" + VALUE + ")元?$");
    private static final Pattern OPTION = Pattern.compile("^(已选择|已选中|已选|未选择|未选中)[:：]?\\s*(.+)$");
    private static final Pattern INLINE_PRICE = Pattern.compile("\\s*[¥￥]\\s*(" + VALUE + ")\\s*$");
    private static final Pattern COUNT_SUFFIX = Pattern.compile("(?:\\s*[（(]\\s*(\\d+)\\s*[）)]|\\s*[·・]\\s*(\\d+))\\s*$");
    private static final Pattern PAGE_COUNT = Pattern.compile("^共\\s*([1-9][0-9]{0,3})\\s*款\\s*[>›〉＞]?$");
    private static final Pattern HEADING = Pattern.compile("^[\\p{L}\\p{N}_ /·&+\\-]{1,24}$");
    private static final Pattern UNAVAILABLE = Pattern.compile("\\s*(?:已售罄|暂时缺货|缺货|不可选|不支持|无货)\\s*$");
    private static final Pattern COUNTER = Pattern.compile("^[1-9][0-9]*\\s*/\\s*[1-9][0-9]*$");
    private static final Pattern TAG = Pattern.compile(".*(?:榜[·・\\s]*第\\d+名|榜单[·・\\s]*第\\d+名|销量TOP\\d+|月销量上涨|同款好评率|同款好评|店铺好评|人逛过|人加购|人加车|达人推荐|人评价为|人买过|人看过|小时内发出).*", Pattern.CASE_INSENSITIVE);
    private static final Set<String> NON_HEADINGS = new HashSet<>(Arrays.asList(
            "大图", "小图", "切换大图", "切换小图", "请选择", "服务保障", "可选多项", "查看全部", "数量",
            "加入购物袋", "加入购物车", "立即购买", "购买方式", "确认", "确定", "关闭", "促销", "送赠品", "包邮", "运费险", "免运费", "热销", "爆款", "热卖", "火爆价"));
    private static final Set<String> FOOTERS = new HashSet<>(Arrays.asList(
            "加入购物袋", "加入购物车", "立即购买", "领券购买", "优惠购买", "确认购买", "确定", "确认"));
    private static final Set<String> HOT = new HashSet<>(Arrays.asList("热销", "爆款", "热卖"));
    static final String SOLD_OUT = "商品已抢光";
    static final String OUT_OF_STOCK = "商品缺货，仅支持到货通知";

    public static final class Snapshot {
        public boolean popup;
        public boolean gallery;
        public List<Group> groups = new ArrayList<>();
        public JSONObject price = new JSONObject();
        public String title = "";
        public List<String> tags = new ArrayList<>();
        public Rect headerImage;
        public Rect smallModeControl;
        public Rect openSpecs;
        public Rect couponSpecEntry;
        public Rect closeGallery;
        public String intervention = "";
        public int declaredCount;
    }

    public static final class ShopSnapshot {
        public Rect entry;
        public boolean shopPage;
        public String name = "";
    }

    static final class ShareControls {
        Rect share;
        Rect copy;
        boolean sheet;
    }

    static ShareControls readShareControls(AccessibilityNodeInfo root) {
        ShareControls result = new ShareControls();
        Rect window = new Rect();
        root.getBoundsInScreen(window);
        List<Label> labels = new ArrayList<>();
        collect(root, null, labels, new HashSet<>(), 0);
        for (Label label : labels) {
            if (!label.enabled) continue;
            String text = compact(label.text);
            if (text.equals("分享") && label.clickable && label.box.top < window.height() * .15
                    && label.box.left > window.width() * .55) result.share = new Rect(label.box);
            if (text.equals("分享给") && label.box.top > window.height() * .4) result.sheet = true;
            if (text.equals("复制链接") && label.box.top > window.height() * .5) {
                result.copy = new Rect(label.actionBox);
                result.sheet = true;
            }
        }
        return result;
    }

    public static final class Group {
        public String name = "";
        public int declaredCount;
        public List<Option> options = new ArrayList<>();
    }

    public static final class Option {
        public String name = "";
        public Rect bounds = new Rect();
        public boolean selected;
        public boolean enabled = true;
        public boolean hot;
        public boolean hotPrice;
        public Long inlinePriceFen;
    }

    private static final class Label {
        final String text;
        final Rect box;
        final Rect actionBox;
        final boolean enabled;
        final boolean selected;
        final boolean clickable;
        final String className;
        final String resourceId;

        Label(String text, Rect box, Rect actionBox, AccessibilityNodeInfo node) {
            this.text = text;
            this.box = new Rect(box);
            this.actionBox = actionBox == null ? new Rect(box) : new Rect(actionBox);
            this.enabled = node.isEnabled();
            this.selected = node.isSelected() || node.isChecked();
            this.clickable = node.isClickable();
            this.className = value(node.getClassName());
            this.resourceId = value(node.getViewIdResourceName());
        }
    }

    private static final class Heading {
        final String name;
        final Rect box;
        final int count;

        Heading(String name, Rect box, int count) {
            this.name = name;
            this.box = box;
            this.count = count;
        }
    }

    private DouyinAdapter() {}

    /** Reads the verified bottom-left store entry or the actual storefront header. */
    public static ShopSnapshot readShop(AccessibilityNodeInfo root) {
        ShopSnapshot result = new ShopSnapshot();
        if (root == null || root.getPackageName() == null
                || !("com.ss.android.ugc.aweme".contentEquals(root.getPackageName())
                || "com.ss.android.ugc.livelite".contentEquals(root.getPackageName()))) return result;
        Rect screen = new Rect();
        root.getBoundsInScreen(screen);
        int width = Math.max(1, screen.right), height = Math.max(1, screen.bottom);
        List<Label> labels = new ArrayList<>();
        collect(root, null, labels, new HashSet<>(), 0);
        for (Label label : labels) { width = Math.max(width, label.box.right); height = Math.max(height, label.box.bottom); }
        float scale = width / 1080f;
        String compactHeaderName = compactStoreName(labels, height, scale);
        if (!compactHeaderName.isEmpty()) {
            result.shopPage = true;
            result.name = compactHeaderName;
            return result;
        }
        Rect tabs = storeTabs(labels, scale, height);
        if (tabs != null) {
            List<Label> fans = new ArrayList<>();
            for (Label label : labels) {
                if (label.enabled && compact(label.text).matches("粉丝[0-9]+(?:,[0-9]{3})*(?:\\.[0-9]+)?[万亿]?\\+?")
                        && label.box.top >= height * .08f && label.box.bottom <= height * .26f
                        && label.box.bottom < tabs.top && tabs.top - label.box.bottom <= 400 * scale
                        && label.box.height() <= 80 * scale) fans.add(label);
            }
            if (fans.size() == 1) {
                result.shopPage = true;
                Label header = fans.get(0);
                Set<String> names = new LinkedHashSet<>();
                for (Label label : labels) {
                    String text = compact(label.text);
                    if (!label.enabled || text.length() < 2 || text.length() > 70 || text.contains("¥")
                            || text.matches("[0-9.,万亿+]+") || text.equals("搜索") || text.equals("关注")
                            || !sameStoreHeader(labels, label, header, scale)
                            || label.box.top < height * .06f || label.box.bottom > header.box.top + 5 * scale
                            || header.box.top - label.box.bottom > 70 * scale
                            || label.box.height() < 40 * scale || label.box.height() > 110 * scale
                            || label.box.width() < 140 * scale || label.box.right > width * .90f) continue;
                    names.add(label.text.trim());
                }
                if (names.size() == 1) result.name = names.iterator().next();
                return result;
            }
        }
        Rect shop = bottomNavigation(labels, new String[]{"进店", "旗舰店"}, .20f, scale, height);
        Rect service = bottomNavigation(labels, new String[]{"客服"}, .35f, scale, height);
        Rect cart = bottomNavigation(labels, new String[]{"购物车", "购物袋", "加购物车"}, .48f, scale, height);
        if (shop != null && service != null && cart != null
                && shop.right <= service.left && service.left - shop.right <= 100 * scale
                && service.right <= cart.left && cart.left - service.right <= 100 * scale
                && Math.abs(shop.top - service.top) <= 16 * scale && Math.abs(shop.top - cart.top) <= 16 * scale
                && Math.abs(shop.bottom - service.bottom) <= 12 * scale && Math.abs(shop.bottom - cart.bottom) <= 12 * scale) {
            result.entry = new Rect(shop);
        }
        return result;
    }

    private static String compactStoreName(List<Label> labels, int height, float scale) {
        Set<String> names = new LinkedHashSet<>();
        for (Label name : labels) {
            if (!name.enabled || !name.resourceId.endsWith(":id/g2p") || name.text.trim().isEmpty()
                    || name.box.top < 65 * scale || name.box.bottom > height * .10f) continue;
            for (Label search : labels) {
                if (search.enabled && search.resourceId.endsWith(":id/g4r") && compact(search.text).equals("搜索")
                        && name.box.right <= search.box.left && search.box.left - name.box.right <= 75 * scale
                        && name.box.centerY() >= search.box.top && name.box.centerY() <= search.box.bottom) {
                    names.add(name.text.trim());
                }
            }
        }
        return names.size() == 1 ? names.iterator().next() : "";
    }

    private static boolean sameStoreHeader(List<Label> labels, Label name, Label fans, float scale) {
        if (Math.abs(name.box.left - fans.box.left) <= 16 * scale) return true;
        for (Label container : labels) {
            Rect box = container.box;
            if (container.enabled && container.clickable
                    && Math.abs(box.left - name.box.left) <= 16 * scale
                    && box.height() <= 190 * scale && box.width() <= 800 * scale
                    && box.contains(name.box) && box.contains(fans.box)) return true;
        }
        return false;
    }

    static String cleanProductTitle(String title, String sourceTitle) {
        String source = sourceTitle.trim();
        if (!source.isEmpty() && title.equals(source)) return title;
        for (String marker : new String[]{"vv", "v"}) {
            if (!title.startsWith(marker)) continue;
            String candidate = title.substring(marker.length()).trim();
            if (candidate.isEmpty()) continue;
            if (!source.isEmpty() && candidate.equalsIgnoreCase(source)) return candidate;
            int first = candidate.codePointAt(0);
            if (Character.UnicodeScript.of(first) == Character.UnicodeScript.HAN
                    || first == '【' || first == '（' || first == '[') return candidate;
        }
        return title;
    }

    private static Rect storeTabs(List<Label> labels, float scale, int height) {
        Rect found = null;
        String[] names = {"综合", "销量", "新品", "价格"};
        for (Label first : labels) {
            if (!first.enabled || !compact(first.text).equals(names[0]) || first.box.top < height * .18f
                    || first.box.bottom > height * .45f || first.box.left > 260 * scale) continue;
            Rect row = new Rect(first.box), previous = first.box;
            boolean complete = true;
            for (int i = 1; i < names.length; i++) {
                Rect next = null;
                for (Label label : labels) {
                    if (!label.enabled || !compact(label.text).equals(names[i])
                            || Math.abs(label.box.centerY() - first.box.centerY()) > 22 * scale
                            || label.box.left < previous.right || label.box.left - previous.right > 240 * scale
                            || label.box.height() < 30 * scale || label.box.height() > 90 * scale
                            || label.box.width() > 180 * scale) continue;
                    if (next != null && !next.equals(label.box)) { complete = false; break; }
                    next = label.box;
                }
                if (!complete || next == null) { complete = false; break; }
                row.right = next.right; row.top = Math.min(row.top, next.top); row.bottom = Math.max(row.bottom, next.bottom);
                previous = next;
            }
            if (!complete) continue;
            if (found != null && !found.equals(row)) return null;
            found = row;
        }
        return found;
    }

    private static Rect bottomNavigation(List<Label> labels, String[] names, float maxRight, float scale, int height) {
        Rect found = null;
        for (Label label : labels) {
            Rect action = label.actionBox;
            if (!label.enabled || !Arrays.asList(names).contains(compact(label.text))
                    || action.left < 0 || action.right > 1080 * scale * maxRight
                    || action.top < height * .89f || action.bottom > height
                    || action.width() < 40 * scale || action.width() > 160 * scale
                    || action.height() < 70 * scale || action.height() > 200 * scale
                    || !action.contains(label.box) || !enabledClickable(labels, action)) continue;
            if (found != null && !found.equals(action)) return null;
            found = new Rect(action);
        }
        return found;
    }

    private static boolean enabledClickable(List<Label> labels, Rect bounds) {
        for (Label label : labels) if (label.enabled && label.clickable && label.box.equals(bounds)) return true;
        return false;
    }

    public static Snapshot read(AccessibilityNodeInfo root) {
        Snapshot result = new Snapshot();
        if (root == null) return result;
        Rect rootBox = new Rect();
        root.getBoundsInScreen(rootBox);
        int width = Math.max(1, rootBox.right);
        int height = Math.max(1, rootBox.bottom);
        List<Label> labels = new ArrayList<>();
        collect(root, null, labels, new HashSet<>(), 0);
        for (Label label : labels) {
            width = Math.max(width, label.box.right);
            height = Math.max(height, label.box.bottom);
        }
        float scale = width / 1080f;
        boolean soldOutHeading = false, soldOutMessage = false;
        for (Label label : labels) {
            String text = compact(label.text);
            if (label.box.top >= 0 && label.box.bottom <= height * .50f) {
                soldOutHeading |= text.equals("当前商品已抢光");
                soldOutMessage |= text.equals("商品已抢光,去看看其他商品吧");
            }
            if (label.box.top >= 0 && label.box.top < height * .18f && label.box.bottom <= height * .20f
                    && label.box.height() <= 100 * scale
                    && text.equals("商品已失效,推荐以下相似商品")) {
                // Recommendations below this banner are different products. Do
                // not expose any title, specification entry or quote from them.
                result.intervention = "商品已失效";
                return result;
            }
        }
        if (soldOutHeading && soldOutMessage) {
            result.intervention = SOLD_OUT;
            return result;
        }
        List<Option> options = options(labels, scale, height);
        result.groups = groups(labels, options, scale, height);
        boolean footer = false;
        for (Label label : labels) {
            String text = compact(label.text);
            footer |= FOOTERS.contains(text) && label.box.top >= height * .79f;
            if (result.intervention.isEmpty()) result.intervention = intervention(text);
        }
        boolean layoutControl = false;
        boolean purchaseMethod = false, checkoutFooter = false;
        for (Label label : labels) {
            String text = compact(label.text);
            purchaseMethod |= text.equals("购买方式");
            checkoutFooter |= label.box.top >= height * .88f
                    && (text.equals("请填写地址") || text.equals("提交订单") || text.equals("立即支付"));
            if (text.equals("切换大图") || text.equals("切换小图") || text.equals("服务保障")) layoutControl = true;
        }
        // Large-picture captions can be far below their group heading. The
        // explicit native switch remains usable before those groups are parsed.
        result.smallModeControl = modeControl(labels, scale, height);
        result.popup = !result.groups.isEmpty() && (footer || layoutControl || purchaseMethod && checkoutFooter)
                || result.smallModeControl != null && footer;
        if (result.popup && !result.groups.isEmpty()) {
            int groupTop = firstGroupTop(labels, result.groups, height);
            result.headerImage = headerImage(labels, groupTop, scale, height);
            result.price = headerPrice(labels, groupTop, result.headerImage, scale, height);
            result.declaredCount = result.groups.get(0).declaredCount;
        } else if (!result.popup) {
            result.groups.clear();
            result.title = title(labels, width, height);
            result.openSpecs = openSpecs(labels, scale, height);
        }
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        LinkedHashSet<Integer> pageCounts = new LinkedHashSet<>();
        boolean galleryCounter = false;
        Rect countEntry = null;
        boolean ambiguousCountEntry = false;
        for (Label label : labels) {
            String text = compact(label.text);
            Matcher count = PAGE_COUNT.matcher(text);
            if (count.matches() && label.box.right >= width * .70f && label.box.top >= height * .14f && label.box.bottom <= height * .90f) {
                pageCounts.add(Integer.parseInt(count.group(1)));
                Rect action = label.actionBox;
                if (label.enabled && action.contains(label.box) && action.left >= width * .70f
                        && action.width() <= 240 * scale && action.height() <= 150 * scale
                        && enabledClickable(labels, action)) {
                    if (countEntry != null && !countEntry.equals(action)) ambiguousCountEntry = true;
                    countEntry = new Rect(action);
                }
            }
            if (label.text.length() <= 75 && label.box.height() <= 100 * scale && TAG.matcher(text).matches()) tags.add(label.text);
            galleryCounter |= COUNTER.matcher(text).matches() && label.box.top < height * .15f;
            if (result.closeGallery == null && label.box.left < width * .18f && label.box.top < height * .18f
                    && (text.equals("返回") || text.equals("上一页") || text.equals("关闭"))) {
                result.closeGallery = new Rect(label.actionBox);
            }
        }
        result.tags = new ArrayList<>(tags);
        if (pageCounts.size() == 1) result.declaredCount = pageCounts.iterator().next();
        result.gallery = !result.popup && galleryCounter;
        if (!result.gallery) result.closeGallery = null;
        if (result.openSpecs == null && !result.popup && !result.gallery && !result.title.isEmpty()
                && root.getPackageName() != null && "com.ss.android.ugc.aweme".contentEquals(root.getPackageName())
                && !hasBottomAddText(labels, height)) {
            result.openSpecs = iconSpecEntry(labels, scale, height);
        }
        if (result.openSpecs == null && !result.popup && !result.gallery && !result.title.isEmpty()
                && !checkoutFooter && pageCounts.size() == 1 && !ambiguousCountEntry) {
            result.openSpecs = countEntry;
        }
        if (!result.popup && !result.gallery && !result.title.isEmpty()
                && "com.ss.android.ugc.aweme".contentEquals(root.getPackageName())
                && !checkoutFooter) {
            result.couponSpecEntry = couponSpecEntry(labels, width, height);
            if (result.openSpecs == null) result.openSpecs = result.couponSpecEntry;
        }
        if (result.intervention.isEmpty() && result.openSpecs == null && !result.popup && !result.gallery) {
            boolean stockNotice = false, alternatives = false;
            for (Label label : labels) {
                String text = compact(label.text);
                if (label.box.top < height * .90f) continue;
                if (text.equals(SOLD_OUT) || text.equals("已抢光") || text.equals("商品已售罄")) {
                    result.intervention = SOLD_OUT;
                    break;
                }
                stockNotice |= label.enabled && label.box.right <= width * .50f && text.equals("到货通知");
                alternatives |= label.enabled && label.box.left >= width * .50f && text.equals("找同款商品");
            }
            if (result.intervention.isEmpty() && stockNotice && alternatives) result.intervention = OUT_OF_STOCK;
        }
        return result;
    }

    static boolean isUnavailable(String reason) {
        return SOLD_OUT.equals(reason) || OUT_OF_STOCK.equals(reason);
    }

    private static void collect(AccessibilityNodeInfo node, Rect clickableParent, List<Label> labels,
                                Set<String> seen, int depth) {
        if (node == null || depth > 55 || labels.size() > 6500) return;
        Rect box = new Rect();
        node.getBoundsInScreen(box);
        Rect action = clickableParent;
        if (node.isClickable() && !box.isEmpty()) action = new Rect(box);
        if (!box.isEmpty() && node.isVisibleToUser()) {
            String[] texts = {value(node.getContentDescription()), value(node.getText())};
            for (String text : texts) {
                if (text.isEmpty()) continue;
                String key = text + "|" + box.toShortString();
                if (seen.add(key)) labels.add(new Label(text, box, action, node));
            }
            if (texts[0].isEmpty() && texts[1].isEmpty() && (node.isClickable() || value(node.getClassName()).contains("Image"))) {
                String key = "|" + box.toShortString();
                if (seen.add(key)) labels.add(new Label("", box, action, node));
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            collect(child, action, labels, seen, depth + 1);
            child.recycle();
        }
    }

    private static List<Option> options(List<Label> labels, float scale, int height) {
        Map<String, Option> found = new LinkedHashMap<>();
        for (Label label : labels) {
            Matcher match = OPTION.matcher(label.text);
            if (!match.matches() || label.box.top < height * .20f || label.box.bottom > height * .90f
                    || label.box.left < 16 * scale || label.box.height() < 20 * scale || label.box.height() > 780 * scale) continue;
            String name = match.group(2).trim();
            Matcher price = INLINE_PRICE.matcher(name);
            Long amount = null;
            if (price.find()) {
                amount = fen(price.group(1));
                name = name.substring(0, price.start()).trim();
            }
            boolean available = label.enabled && !UNAVAILABLE.matcher(name).find();
            name = UNAVAILABLE.matcher(name).replaceAll("").trim();
            if (name.isEmpty()) continue;
            Option option = new Option();
            option.name = name;
            option.bounds = new Rect(label.box);
            option.selected = label.selected || match.group(1).startsWith("已");
            option.enabled = available;
            option.inlinePriceFen = amount;
            String key = normalize(name) + "|" + label.box.toShortString();
            Option previous = found.get(key);
            if (previous == null || option.selected && !previous.selected) found.put(key, option);
        }
        List<Option> values = new ArrayList<>(found.values());
        values.sort(Comparator.comparingInt((Option option) -> option.bounds.top).thenComparingInt(option -> option.bounds.left));
        for (Label label : labels) {
            String marker = compact(label.text);
            if (!HOT.contains(marker) && !"火爆价".equals(marker)) continue;
            Option match = null;
            int hits = 0;
            for (Option option : values) {
                Rect box = option.bounds;
                if (box.left <= label.box.left && label.box.right <= box.right + 16 * scale
                        && Math.abs(label.box.top - box.top) <= 65 * scale) {
                    match = option;
                    hits++;
                }
            }
            if (hits == 1) {
                if ("火爆价".equals(marker)) match.hotPrice = true;
                else match.hot = true;
            }
        }
        return values;
    }

    private static List<Group> groups(List<Label> labels, List<Option> options, float scale, int height) {
        Set<String> optionNames = new HashSet<>();
        Set<Rect> optionBoxes = new HashSet<>();
        for (Option option : options) {
            optionNames.add(normalize(option.name));
            optionBoxes.add(option.bounds);
        }
        Map<Integer, Heading> headings = new LinkedHashMap<>();
        for (Label label : labels) {
            String name = label.text.trim();
            int count = 0;
            Matcher suffix = COUNT_SUFFIX.matcher(name);
            if (suffix.find()) {
                count = positiveCount(suffix.group(1) == null ? suffix.group(2) : suffix.group(1));
                name = name.substring(0, suffix.start()).trim();
            }
            name = name.replaceAll("[:：]+$", "").trim();
            Rect box = label.box;
            if (box.left < 20 * scale || box.left > 160 * scale || box.top < height * .22f || box.bottom > height * .90f
                    || box.height() < 18 * scale || box.height() > 110 * scale
                    || box.width() > (count > 0 ? 820 : 530) * scale
                    || !(HEADING.matcher(name).matches() || count > 0 && name.length() <= 45)
                    || name.matches("[0-9]+") || NON_HEADINGS.contains(compact(name))
                    || optionNames.contains(normalize(name)) || optionBoxes.contains(box)) continue;
            boolean nearOption = false;
            for (Option option : options) {
                int gap = option.bounds.top - box.bottom;
                if (gap >= -8 * scale && gap <= 220 * scale) nearOption = true;
            }
            if (nearOption) headings.putIfAbsent(box.top, new Heading(name, new Rect(box), count));
        }
        List<Heading> ordered = new ArrayList<>(headings.values());
        ordered.sort(Comparator.comparingInt(heading -> heading.box.top));
        List<Group> result = new ArrayList<>();
        for (int index = 0; index < ordered.size(); index++) {
            Heading heading = ordered.get(index);
            int end = index + 1 < ordered.size() ? ordered.get(index + 1).box.top : (int)(height * .90f);
            for (Label label : labels) {
                if (compact(label.text).startsWith("服务保障") && label.box.top > heading.box.bottom) end = Math.min(end, label.box.top);
            }
            Map<String, Option> values = new LinkedHashMap<>();
            for (Option option : options) {
                if (option.bounds.top < heading.box.bottom - 8 * scale || option.bounds.top >= end) continue;
                String key = normalize(option.name);
                Option previous = values.get(key);
                if (previous == null || option.selected && !previous.selected) values.put(key, option);
            }
            if (values.isEmpty()) continue;
            Group group = new Group();
            group.name = heading.name;
            group.declaredCount = heading.count;
            group.options = new ArrayList<>(values.values());
            result.add(group);
        }
        return result;
    }

    private static int firstGroupTop(List<Label> labels, List<Group> groups, int fallback) {
        String first = normalize(groups.get(0).name);
        int top = fallback;
        for (Label label : labels) {
            String heading = COUNT_SUFFIX.matcher(label.text.trim()).replaceFirst("");
            if (normalize(heading).equals(first) && label.box.left < 200) top = Math.min(top, label.box.top);
        }
        return top;
    }

    private static Rect headerImage(List<Label> labels, int groupTop, float scale, int height) {
        List<Rect> semantic = new ArrayList<>();
        List<Rect> squares = new ArrayList<>();
        for (Label label : labels) {
            Rect box = label.box;
            if (box.left < 20 * scale || box.left > 140 * scale || box.top < height * .12f || box.bottom > groupTop
                    || box.width() < 130 * scale || box.width() > 300 * scale || Math.abs(box.width() - box.height()) > 22 * scale
                    || groupTop - box.bottom > 300 * scale) continue;
            if (compact(label.text).equals("商品图片") || compact(label.text).equals("商品图")) semantic.add(box);
            if (label.clickable || label.className.contains("Image")) squares.add(box);
        }
        List<Rect> candidates = semantic.isEmpty() ? squares : semantic;
        if (candidates.isEmpty()) return null;
        candidates.sort(Comparator.comparingInt((Rect box) -> -box.width()).thenComparingInt(box -> box.top));
        Rect first = candidates.get(0);
        for (Rect box : candidates) {
            if (Math.abs(first.left - box.left) > 22 * scale || Math.abs(first.top - box.top) > 22 * scale) return null;
        }
        return new Rect(first);
    }

    private static Rect modeControl(List<Label> labels, float scale, int height) {
        Label preferred = null;
        for (Label label : labels) {
            String text = compact(label.text);
            if (!text.equals("切换小图") || !label.enabled || !label.clickable || label.box.left < 820 * scale
                    || label.box.top < height * .20f || label.box.bottom > height * .90f) continue;
            if (preferred != null && Math.abs(preferred.box.top - label.box.top) > 50 * scale) return null;
            if (preferred == null) preferred = label;
        }
        return preferred == null ? null : new Rect(preferred.box);
    }

    private static JSONObject headerPrice(List<Label> labels, int groupTop, Rect image, float scale, int height) {
        List<Label> candidates = new ArrayList<>();
        LinkedHashSet<String> texts = new LinkedHashSet<>();
        for (Label label : labels) {
            if (label.box.left < 200 * scale || label.box.bottom > groupTop || label.box.top < height * .12f) continue;
            if (image != null && (label.box.bottom < image.top - 25 * scale || label.box.top > image.bottom + 45 * scale)) continue;
            String text = compact(label.text);
            if (text.contains("¥") && text.contains("起")) return new JSONObject();
            texts.add(text);
            if (SINGLE.matcher(text).matches()) candidates.add(label);
        }
        for (Label left : candidates) {
            for (Label right : candidates) {
                if (left.box.right <= right.box.left && Math.abs(left.box.top - right.box.top) <= 50 * scale) {
                    texts.add(compact(left.text) + compact(right.text));
                }
            }
        }
        Map<String, JSONObject> pairs = new LinkedHashMap<>();
        Set<Long> savings = new LinkedHashSet<>();
        for (String text : texts) {
            Matcher saved = SAVINGS.matcher(text);
            if (saved.matches() && fen(saved.group(1)) != null) savings.add(fen(saved.group(1)));
            Matcher reversed = PAID_FIRST.matcher(text);
            Matcher pair = PAIR.matcher(reversed.matches()
                    ? reversed.group(2) + "¥" + reversed.group(3) + "实付价¥" + reversed.group(1) : text);
            if (!pair.matches()) continue;
            Long before = fen(pair.group(2));
            Long after = fen(pair.group(4));
            if (before == null || after == null || after > before) continue;
            JSONObject price = new JSONObject();
            put(price, "price_before_coupon_fen", before);
            put(price, "price_after_coupon_fen", after);
            put(price, "price_before_label", pair.group(1) == null ? (pair.group(3).contains("券后") ? "券前价" : "展示价") : pair.group(1));
            put(price, "price_after_label", pair.group(3));
            put(price, "price_text", text);
            pairs.put(before + "|" + after + "|" + pair.group(3), price);
        }
        if (pairs.size() == 1) {
            JSONObject price = pairs.values().iterator().next();
            long difference = price.optLong("price_before_coupon_fen") - price.optLong("price_after_coupon_fen");
            if (savings.size() == 1 && !savings.contains(difference)) return new JSONObject();
            return price;
        }
        if (!pairs.isEmpty()) return new JSONObject();
        Map<String, JSONObject> singles = new LinkedHashMap<>();
        for (String text : texts) {
            Matcher single = SINGLE.matcher(text);
            if (!single.matches()) continue;
            String label = single.group(1);
            Long amount = fen(single.group(2));
            if (amount == null) continue;
            boolean after = label != null && Pattern.matches(AFTER, label);
            JSONObject price = new JSONObject();
            put(price, "price_before_coupon_fen", after ? JSONObject.NULL : amount);
            put(price, "price_after_coupon_fen", after ? amount : JSONObject.NULL);
            put(price, "price_before_label", after ? JSONObject.NULL : label == null ? "页面价" : label);
            put(price, "price_after_label", after ? label : JSONObject.NULL);
            put(price, "price_text", text);
            singles.put(amount + "|" + label, price);
        }
        return singles.size() == 1 ? singles.values().iterator().next() : new JSONObject();
    }

    private static Rect openSpecs(List<Label> labels, float scale, int height) {
        Rect addToCart = null;
        for (Label label : labels) {
            // Use one verified entry consistently. Keep the exact native text
            // bounds so a shared parent containing purchase is never targeted.
            if (!label.enabled || !isAddToCartLabel(label.text)
                    || label.box.top < height * .90f || label.box.bottom > height) continue;
            if (addToCart == null || addToCart.contains(label.box)) {
                addToCart = new Rect(label.box);
            } else if (!label.box.contains(addToCart)) {
                return null;
            }
        }
        return addToCart;
    }

    private static boolean hasBottomAddText(List<Label> labels, int height) {
        for (Label label : labels) if (label.enabled && isAddToCartLabel(label.text)
                && label.box.top >= height * .90f && label.box.bottom <= height) return true;
        return false;
    }

    private static boolean isAddToCartLabel(String text) {
        String label = compact(text);
        return label.equals("加购") || label.equals("加购物车");
    }

    private static Rect couponSpecEntry(List<Label> labels, int width, int height) {
        Rect entry = null;
        for (Label label : labels) {
            Rect box = label.box;
            Rect action = label.actionBox;
            if (!label.enabled || !compact(label.text).equals("领券购买")
                    || box.top < height * .90f || box.left < width * .65f
                    || action.left < width * .55f || action.right < width * .90f
                    || action.top < height * .88f || action.bottom > height
                    || !action.contains(box) || !hasQuotedPurchase(labels, action, true)) continue;
            if (entry != null && !entry.equals(box)) return null;
            entry = new Rect(box);
        }
        return entry;
    }

    /**
     * Verified native Douyin footer: cart navigation | small image-only add area |
     * larger, quoted purchase CTA. Every boundary comes from nodes;
     * a missing neighbour, hidden label, or second candidate prevents any click.
     */
    private static Rect iconSpecEntry(List<Label> labels, float scale, int height) {
        Rect found = null;
        float width = scale * 1080;
        for (Label candidate : labels) {
            Rect box = candidate.box;
            if (!candidate.text.isEmpty() || !candidate.enabled || !candidate.clickable
                    || !candidate.className.contains("ViewGroup") || !candidate.actionBox.equals(box)
                    || box.top < height * .90f || box.bottom > height
                    || box.left < width * .33f || box.right > width * .63f
                    || box.width() < 150 * scale || box.width() > 240 * scale
                    || box.height() < 90 * scale || box.height() > 170 * scale) continue;
            boolean emptyImage = false, hasText = false, cartAtLeft = false, purchaseAtRight = false;
            for (Label nested : labels) {
                if (box.contains(nested.box)) {
                    if (!nested.text.isEmpty()) hasText = true;
                    if (nested.enabled && nested.text.isEmpty() && nested.className.contains("Image")
                            && nested.actionBox.equals(box) && nested.box.width() >= 45 * scale
                            && nested.box.width() <= 120 * scale && nested.box.height() >= 45 * scale
                            && nested.box.height() <= 120 * scale
                            && Math.abs(nested.box.centerX() - box.centerX()) <= 40 * scale) emptyImage = true;
                }
                Rect action = nested.actionBox;
                if (nested.enabled && compact(nested.text).equals("购物车")
                        && action.width() >= 40 * scale && action.width() <= 130 * scale
                        && action.right <= box.left && box.left - action.right <= 80 * scale
                        && Math.abs(action.bottom - box.bottom) <= 12 * scale
                        && Math.abs(action.top - box.top) <= 45 * scale) cartAtLeft = true;
                String text = compact(nested.text);
                if (nested.enabled && (text.equals("现在下单") || text.equals("领券抢购") || text.equals("去抢购"))
                        && action.left >= box.right && action.left - box.right <= 10 * scale
                        && Math.abs(action.top - box.top) <= 12 * scale
                        && Math.abs(action.bottom - box.bottom) <= 12 * scale
                        && action.width() >= box.width() * 1.8f && action.right >= width * .85f
                        && hasQuotedPurchase(labels, action, false)) purchaseAtRight = true;
            }
            if (!emptyImage || hasText || !cartAtLeft || !purchaseAtRight) continue;
            if (found != null && !found.equals(box)) return null;
            found = new Rect(box);
        }
        return found;
    }

    private static boolean hasQuotedPurchase(List<Label> labels, Rect purchase, boolean requireDiscountLabel) {
        boolean labelFound = false, priceFound = false;
        for (Label label : labels) {
            if (!label.enabled || !purchase.contains(label.box) || !label.actionBox.equals(purchase)) continue;
            String text = compact(label.text);
            labelFound |= text.equals("券后价") || text.equals("补贴价");
            priceFound |= SINGLE.matcher(text).matches();
        }
        return priceFound && (!requireDiscountLabel || labelFound);
    }

    private static String title(List<Label> labels, int width, int height) {
        String best = "";
        int score = Integer.MIN_VALUE;
        for (Label label : labels) {
            String text = label.text.trim();
            String flat = compact(text);
            if (flat.length() < 12 || flat.length() > 150 || flat.contains("¥") || text.split("\\n").length > 3
                    || OPTION.matcher(text).matches() || flat.matches(".*(?:退货包邮券|现在下单|预估到手|已售|请选择|商品评价|用户评价|隐私|允许访问|免运费).*")) continue;
            if (label.box.top < height * .22f || label.box.bottom > height * .85f || label.box.height() > height * .17f) continue;
            int candidate = Math.min(flat.length(), 65);
            if (label.box.left < width * .20f && label.box.right > width * .50f) candidate += 25;
            String id = label.resourceId.toLowerCase();
            if (id.contains("title") || id.contains("goods_name") || id.contains("product_name")) candidate += 60;
            if (TAG.matcher(flat).matches()) candidate -= 80;
            if (candidate > score) {
                score = candidate;
                best = text;
            }
        }
        return best;
    }

    private static String intervention(String text) {
        if (text.length() > 85) return "";
        if (text.equals("网络异常") || text.equals("商品已下架") || text.equals("商品不存在") || text.equals("无法访问页面")) return text;
        if (text.equals("身份验证") || text.equals("请输入短信验证码")) return "抖音要求短信身份验证，请在手机上完成后继续";
        if (text.matches(".*(?:请完成安全验证|请完成验证|实名认证|手机号一键登录|手机号登录|验证码登录|登录后继续|滑动拼图|身份验证).*")) return text;
        return "";
    }

    private static Long fen(String value) {
        try {
            return new BigDecimal(value.replace(",", "")).movePointRight(2).longValueExact();
        } catch (ArithmeticException | NumberFormatException error) {
            return null;
        }
    }

    private static int positiveCount(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException error) {
            return 0;
        }
    }

    private static String compact(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).replace('￥', '¥').replaceAll("\\s+", "");
    }

    public static String normalize(String value) {
        return compact(value == null ? "" : value).toLowerCase().replaceAll("[\\p{P}\\p{S}_]+", "");
    }

    private static String value(CharSequence value) {
        return value == null ? "" : value.toString().trim();
    }

    private static void put(JSONObject object, String key, Object value) {
        try {
            object.put(key, value);
        } catch (JSONException error) {
            throw new IllegalArgumentException("Invalid parsed price", error);
        }
    }
}
