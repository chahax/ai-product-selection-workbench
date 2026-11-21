from __future__ import annotations

import re
import unicodedata

from .android import cents, normalize


PACKAGE = "com.ss.android.ugc.livelite"
_SELECTED = re.compile(r"^(已选择|已选中|已选|未选择|未选中)(.+)$")
_SUBSIDY = re.compile(r"^[¥￥](\d+(?:\.\d{1,2})?)补贴价[¥￥](\d+(?:\.\d{1,2})?)$")
_SAVINGS = re.compile(r"^加补\+平台券等共省[¥￥]?(\d+(?:\.\d{1,2})?)$")
_TAGS = (
    re.compile(r".{1,30}?榜(?:单)?[·・\s]*第\d+名"),
    re.compile(r"月销量上涨(?:超)?\d+(?:\.\d+)?(?:倍|%)"),
    re.compile(r"同款好评率\d{1,3}(?:\.\d+)?%"),
    re.compile(r"\d+(?:\.\d+)?(?:千|万|亿)?\+?人逛过"),
    re.compile(r"\d+(?:\.\d+)?(?:千|万|亿)?\+?人评价为[\"“]?正品[\"”]?"),
    re.compile(r"\d+(?:\.\d+)?(?:千|万|亿)?\+?(?:位|人)?达人推荐"),
    re.compile(r"\d+(?:\.\d+)?(?:千|万|亿)?\+?人加购"),
)


def _visible_labels(tree):
    if tree is None:
        return
    for node in tree.iter("node"):
        box = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
        if not box:
            continue
        x1, y1, x2, y2 = map(int, box.groups())
        if x1 >= x2 or y1 >= y2:
            continue
        for text in dict.fromkeys(node.get(key, "").strip() for key in ("text", "content-desc")):
            if text:
                yield text, (x1, y1, x2, y2)


def _compact(text):
    return re.sub(r"\s+", "", unicodedata.normalize("NFKC", text))


def single_option_quote(tree):
    """Read the confirmed standalone Mall subsidy layout from exact UI labels."""
    labels = list(_visible_labels(tree))
    groups = {box for text, box in labels if _compact(text) == "颜色分类"}
    if len(groups) != 1:
        return None
    group = next(iter(groups))
    section_end = min(
        (box[1] for text, box in labels
         if box[1] > group[3] and _compact(text).startswith("服务保障")),
        default=group[3] + 4 * (group[3] - group[1]),
    )
    options = {}
    for text, box in labels:
        option = _SELECTED.fullmatch(text)
        if option and group[3] <= box[1] < box[3] <= section_end:
            name = option[2].strip()
            options.setdefault(normalize(name), (name, option[1] in ("已选择", "已选中", "已选")))
    if len(options) != 1:
        return None
    name, selected = next(iter(options.values()))
    if not name or not selected:
        return None

    headlines = {}
    savings = set()
    for text, box in labels:
        if box[3] > group[1]:
            continue
        compact = _compact(text)
        match = _SUBSIDY.fullmatch(compact)
        if match:
            headlines.setdefault((cents(match[1]), cents(match[2])), text)
        match = _SAVINGS.fullmatch(compact)
        if match:
            savings.add(cents(match[1]))
    if len(headlines) != 1:
        return None
    (before, after), raw = next(iter(headlines.items()))
    if after > before or (savings and savings != {before - after}):
        return None
    result = {
        "name": name,
        "price_before_coupon_fen": before,
        "price_after_coupon_fen": after,
        "price_after_label": "补贴价",
        "price_text": raw,
    }
    if savings:
        result["savings_fen"] = before - after
    return result


def product_tags(tree, title="", *, row_bounds=None):
    """Return recognized chip labels and the chip row's vertical bounds."""
    labels = list(_visible_labels(tree))
    rows = {}
    for text, box in sorted(labels, key=lambda item: (item[1][1], item[1][0])):
        key = _compact(text)
        if any(pattern.fullmatch(key) for pattern in _TAGS):
            rows.setdefault((box[1], box[3]), {}).setdefault(key, text)
    if row_bounds is not None:
        return list(rows.get(tuple(row_bounds), {}).values()), tuple(row_bounds)
    if not rows:
        return [], None

    title_key = normalize(title)
    candidates = []
    for bounds, tags in rows.items():
        top, bottom = bounds
        height = bottom - top
        anchors = [box[3] for text, box in labels
                   if title_key and title_key in normalize(text)
                   and box[3] <= top and box[3] - box[1] <= 8 * height
                   and top - box[3] <= 12 * height]
        if anchors:
            candidates.append((top - max(anchors), -len(tags), bounds))
    if candidates:
        bounds = min(candidates)[2]
    else:
        # Without a title on this frame, trust only a unique row of multiple recognized chips.
        populated = [bounds for bounds, tags in rows.items() if len(tags) >= 2]
        if len(populated) != 1:
            return [], None
        bounds = populated[0]
    return list(rows[bounds].values()), bounds


def merge_product_tags(*frames):
    tags = {}
    for frame in frames:
        for tag in frame:
            tags.setdefault(_compact(tag), tag)
    return list(tags.values())

