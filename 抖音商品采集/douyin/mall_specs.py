"""Read exact SKU options and the price shown for each confirmed selection."""

from __future__ import annotations

import re
import subprocess
import time
import unicodedata
from dataclasses import dataclass, replace
from itertools import product
from pathlib import Path

from . import android, images, mall
from .android import cents, normalize
from .images import bounds


_VALUE = r"\d+(?:\.\d{1,2})?"
_MONEY = rf"¥({_VALUE})"
_PROMOTION = r"预估券后价|预估到手价|百亿补贴价|平台补贴价|券后价|补贴价|到手价|活动价|优惠价|限时价|拼团价|秒杀价|首单价|首件价|会员价|新人价"
_BEFORE = r"展示价|优惠前|券前价|原价|日常价"
_PAIR = re.compile(rf"(?P<before_label>{_BEFORE})?¥(?P<before>{_VALUE})(?P<after_label>{_PROMOTION})¥(?P<after>{_VALUE})")
_SINGLE = re.compile(rf"(?P<label>{_PROMOTION}|{_BEFORE})?¥(?P<value>{_VALUE})")
_SAVINGS = re.compile(rf"(?:加补\+平台券等共省|券共减|已优惠|共省)¥?({_VALUE})元?")
_OPTION = re.compile(r"^(已选择|已选中|已选|未选择|未选中)[:：]?\s*(.+)$")
_INLINE_PRICE = re.compile(rf"\s*[¥￥]\s*({_VALUE})\s*$")
_COUNT = re.compile(r"(?:\s*[（(]\s*(?P<parentheses>\d+)\s*[）)]|\s*[·・]\s*(?P<suffix>\d+))\s*$")
_BADGES = ("热销", "爆款", "热卖")
_UNAVAILABLE = re.compile(r"\s*(?:已售罄|暂时缺货|缺货|不可选|不支持|无货)\s*$")
_HEADING = re.compile(r"^[\w\u3400-\u9fff /·&+\-]{1,24}$")
_NON_HEADINGS = {"大图", "小图", "切换大图", "切换小图", "请选择", "服务保障", "可选多项", "查看全部", "数量", "加入购物车", "立即购买", "确认", "确定", "关闭", "促销", "送赠品", "包邮", "运费险", "免运费"}
_FOOTERS = {"加入购物车", "立即购买", "确认", "确定", "优惠购买", "领券购买", "确认购买"}


@dataclass(frozen=True)
class Label:
    text: str
    box: tuple[int, int, int, int]
    node: object


@dataclass(frozen=True)
class Option:
    name: str
    box: tuple[int, int, int, int]
    selected: bool
    enabled: bool
    hot: bool
    inline_price: int | None


@dataclass(frozen=True)
class Group:
    name: str
    top: int
    count: int | None
    options: dict[str, Option]


def _compact(value):
    return re.sub(r"\s+", "", unicodedata.normalize("NFKC", value))


def _labels(tree):
    for node in tree.iter("node"):
        box = bounds(node)
        if not box or not (0 <= box[0] < box[2] <= 1080 and 0 <= box[1] < box[3] <= 2400):
            continue
        words = dict.fromkeys(node.get(key, "").strip() for key in ("content-desc", "text"))
        for word in words:
            if word:
                yield Label(word, box, node)


def _option(label, *, tile=False):
    left, top, right, bottom = label.box
    match = _OPTION.fullmatch(label.text)
    max_height = 520 if tile else 220
    if match is None or not (25 <= left <= 950 and 600 <= top < bottom <= 2140 and 24 <= bottom - top <= max_height and right - left >= 30):
        return None
    name = match[2].strip()
    inline = _INLINE_PRICE.search(name)
    if inline:
        name = name[:inline.start()].strip()
    available = label.node.get("enabled") != "false" and _UNAVAILABLE.search(name) is None
    name = _UNAVAILABLE.sub("", name).strip()
    selected = match[1] in ("已选择", "已选中", "已选") or label.node.get("selected") == "true"
    hot = any(
        child.get(attr, "").strip() in _BADGES
        for child in label.node.iter("node") for attr in ("text", "content-desc")
    )
    return Option(name, label.box, selected, available, hot, cents(inline[1]) if inline else None) if name else None


def _groups(labels):
    badges = [label.box for label in labels if label.text in _BADGES]
    options = sorted(
        (option for label in labels if (option := _option(label)) is not None),
        key=lambda item: (item.box[1], item.box[0]),
    )
    for badge in badges:
        candidates = [
            index for index, option in enumerate(options)
            if option.box[0] <= badge[0] < badge[2] <= option.box[2] + 12
            and abs(badge[1] - option.box[1]) <= 50
        ]
        if len(candidates) == 1 and not options[candidates[0]].hot:
            index = candidates[0]
            options[index] = replace(options[index], hot=True)
    option_boxes = {option.box for option in options}
    option_names = {normalize(option.name) for option in options}
    headings = {}
    for label in labels:
        left, top, right, bottom = label.box
        count = _COUNT.search(label.text)
        name = (label.text[:count.start()] if count else label.text).rstrip("：: ").strip()
        if not (
            28 <= left <= 105 and 650 <= top < bottom <= 2070 and 20 <= bottom - top <= 95
            and right - left <= 490 and label.box not in option_boxes
            and name not in _NON_HEADINGS and normalize(name) not in option_names
            and _HEADING.fullmatch(name) and not name.isdigit()
        ):
            continue
        if not any(bottom <= option.box[1] <= bottom + 185 for option in options):
            continue
        total = int(count["parentheses"] or count["suffix"]) if count else None
        headings.setdefault(top, (name, bottom, total))
    groups = []
    for top, (name, bottom, count) in sorted(headings.items()):
        next_heading = min((other for other in headings if other > top), default=2140)
        stop = min(
            (label.box[1] for label in labels if label.box[1] >= bottom and label.text.startswith("服务保障")),
            default=2140,
        )
        values = {}
        for option in options:
            if bottom <= option.box[1] < min(stop, next_heading):
                key = normalize(option.name)
                previous = values.get(key)
                if not previous or option.selected and not previous.selected:
                    values[key] = option
        if values:
            groups.append(Group(name, top, count, values))
    return groups


def _header_price(labels, group_top):
    headings = [
        label for label in labels
        if 370 <= label.box[1] < label.box[3] <= group_top and label.box[0] >= 210
    ]
    entries = list(dict.fromkeys(_compact(label.text) for label in headings))
    split = [
        label for label in headings if _SINGLE.fullmatch(_compact(label.text))
    ]
    for left in split:
        for right in split:
            if left.box[2] <= right.box[0] and abs(left.box[1] - right.box[1]) <= 45:
                entries.append(_compact(left.text) + _compact(right.text))
    matches = {}
    for value in entries:
        if match := _PAIR.fullmatch(value):
            before, after = cents(match["before"]), cents(match["after"])
            if after <= before:
                matches[(before, after, match["after_label"])] = (match["before_label"], value)
    if len(matches) == 1:
        (before, after, after_label), (before_label, raw) = next(iter(matches.items()))
        savings = {cents(match[1]) for value in entries if (match := _SAVINGS.fullmatch(value))}
        if not savings or savings == {before - after}:
            return {
                "price_before_coupon_fen": before,
                "price_after_coupon_fen": after,
                "price_before_label": before_label or ("券前价" if "券后价" in after_label else "展示价"),
                "price_after_label": after_label,
                "price_text": raw,
            }
    if matches or any(_PAIR.search(value.replace("起", "")) for value in entries):
        return None
    singles = {(cents(match["value"]), match["label"]) for value in entries if (match := _SINGLE.fullmatch(value))}
    if len(singles) != 1:
        return None
    amount, label = singles.pop()
    after = label is not None and re.fullmatch(_PROMOTION, label) is not None
    return {
        "price_before_coupon_fen": None if after else amount,
        "price_after_coupon_fen": amount if after else None,
        "price_before_label": None if after else label or "页面价",
        "price_after_label": label if after else None,
    }


def _popup(tree):
    if tree is None:
        return (), ()
    labels = tuple(_labels(tree))
    groups = _groups(labels)
    if not groups:
        return (), ()
    has_footer = any(label.text in _FOOTERS and label.box[1] >= 2060 for label in labels)
    has_control = any(
        (label.text in ("切换大图", "切换小图", "大图", "小图") or label.text.startswith("服务保障"))
        and label.box[1] >= groups[0].top
        for label in labels
    )
    return (labels, groups) if has_footer or has_control else ((), ())


def popup_open(tree):
    """Require readable variant rows and a control within the panel."""
    return bool(_popup(tree)[1]) or mall.single_option_quote(tree) is not None


def _confirmed(groups, keys):
    return len(groups) >= len(keys) and all(
        (option := group.options.get(key)) is not None
        and option.enabled and option.selected
        and sum(candidate.selected for candidate in group.options.values()) == 1
        for group, key in zip(groups, keys)
    )


def _choose(phone, labels, groups, keys, seen_names):
    changed = False
    for depth, key in enumerate(keys):
        if len(groups) != len(seen_names) or any(
            normalize(group.name) != expected for group, expected in zip(groups, seen_names)
        ):
            return labels, groups, False, changed
        option = groups[depth].options.get(key)
        if option is None or not option.enabled:
            return labels, groups, False, changed
        if not _confirmed(groups, keys[:depth + 1]):
            x1, y1, x2, y2 = option.box
            phone.tap((x1 + x2) // 2, (y1 + y2) // 2)
            changed = True
            for delay in (0.45, 0.7):
                time.sleep(delay)
                labels, groups = _popup(phone.tree(timeout=6))
                if _confirmed(groups, keys[:depth + 1]):
                    break
            else:
                return labels, groups, False, changed
    return labels, groups, True, changed


def _small_mode(phone, tree):
    """The displayed control names the mode it switches to."""
    if tree is None:
        return None
    labels = tuple(_labels(tree))
    controls = [label for label in labels if label.text in ("切换小图", "小图")
                and 840 <= label.box[0] < label.box[2] <= 1080
                and 650 <= label.box[1] < label.box[3] <= 2070]
    if not controls:
        return tree
    if any(abs(label.box[1] - controls[0].box[1]) > 40 for label in controls):
        return None
    control = min(controls, key=lambda label: label.text != "切换小图")
    left, top, right, bottom = control.box
    phone.tap((left + right) // 2, (top + bottom) // 2)
    for delay in (0.4, 0.6):
        time.sleep(delay)
        current = phone.tree(timeout=6)
        if current is None:
            continue
        words = {label.text for label in _labels(current)}
        if "切换小图" not in words and ("切换大图" in words or "大图" in words):
            return current
    return None


def _header_thumbnail(tree, group_top):
    """Find the square picture next to the confirmed SKU price."""
    candidates = set()
    for node in tree.iter("node"):
        box = bounds(node)
        if not box:
            continue
        left, top, right, bottom = box
        width, height = right - left, bottom - top
        if (28 <= left <= 125 and 135 <= width <= 280 and 135 <= height <= 280
                and abs(width - height) <= 18 and 330 <= top < bottom <= group_top
                and right <= 300 and group_top - bottom <= 240):
            candidates.add(box)
    if not candidates:
        return None
    ordered = sorted(candidates, key=lambda box: (-(box[2] - box[0]), box[1]))
    first = ordered[0]
    # Accessibility can expose the same image and its border as nested squares.
    if any(abs(box[0] - first[0]) > 20 or abs(box[1] - first[1]) > 20 for box in ordered[1:]):
        return None
    return first


def _selected_header_image(phone, keys, group_names, name, target):
    tree = phone.tree(timeout=5)
    labels, groups = _popup(tree)
    if groups:
        if [normalize(group.name) for group in groups] != group_names or not _confirmed(groups, keys):
            return None, "图片采集前未核对到当前规格组合"
        group_top = groups[0].top
    else:
        selected = mall.single_option_quote(tree)
        headings = [label.box[1] for label in _labels(tree) if _compact(label.text) == "颜色分类"] if tree is not None else []
        if selected is None or normalize(selected["name"]) != normalize(name) or len(set(headings)) != 1:
            return None, "图片采集前未核对到当前规格组合"
        group_top = headings[0]
    box = _header_thumbnail(tree, group_top)
    if box is None:
        return None, "没有定位到当前规格顶部图片"
    left, top, right, bottom = box
    frame = phone.screen()
    if frame.size != images.SCREEN_SIZE:
        return None, "规格图片画面尺寸发生变化"
    thumbnail = frame.crop((left + 5, top + 5, right - 5, bottom - 5))
    time.sleep(0.25)
    settled = phone.screen().crop((left + 5, top + 5, right - 5, bottom - 5))
    if images._diff(thumbnail, settled) > 3:
        time.sleep(0.4)
        latest = phone.screen().crop((left + 5, top + 5, right - 5, bottom - 5))
        if images._diff(settled, latest) > 3:
            return None, "顶部规格图片仍在更新"
        settled = latest
    phone.tap((left + right) // 2, (top + bottom) // 2)
    preview = None
    for delay in (0.4, 0.55):
        time.sleep(delay)
        preview = images.preview_crop(phone.screen())
        if preview is not None:
            break
    if preview is None:
        # Back only when the image counter confirms a gallery really opened.
        current = phone.tree(timeout=4)
        if current is not None and not _popup(current)[1] and any(
            re.fullmatch(r"\d+\s*/\s*\d+", label.text) for label in _labels(current)
        ):
            phone.back()
            time.sleep(0.3)
        return None, "顶部图片未打开为可核对的完整预览"
    current = phone.tree(timeout=4)
    exact_name = images._preview_sku_label(current, android, (name,))
    matches = exact_name is not None or images._thumb_match(settled, preview) < 18
    phone.tap(*images.PREVIEW_BACK)
    time.sleep(0.3)
    returned = phone.tree(timeout=5)
    _, current_groups = _popup(returned)
    if groups:
        unchanged = _confirmed(current_groups, keys) and [normalize(group.name) for group in current_groups] == group_names
    else:
        selected = mall.single_option_quote(returned)
        unchanged = selected is not None and normalize(selected["name"]) == normalize(name)
    if not unchanged:
        return None, "完整图片返回后未核对到原规格组合"
    if not matches:
        return None, "完整图片与当前规格顶部图片未匹配"
    return images._save(preview, target, "抖音商城已选规格顶部图片预览裁切"), None


def collect_quotes(phone, tree, *, limit=24, output=None):
    """Read only confirmed combinations and retain exact displayed promotion labels."""
    tree = _small_mode(phone, tree)
    if tree is None:
        return [], "未能切回可核对的小图规格模式"
    labels, groups = _popup(tree)
    if not groups:
        known = mall.single_option_quote(tree)
        if known is not None:
            known["specifications"] = {"颜色分类": known["name"]}
            known["price_before_label"] = "展示价"
            if output is not None:
                folder = Path(output)
                folder.mkdir(parents=True, exist_ok=True)
                try:
                    known["image"], image_note = _selected_header_image(
                        phone, (), [], known["name"], folder / "规格图-01.png",
                    )
                except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
                    known["image"], image_note = None, f"顶部规格图片未完成核对（{type(error).__name__}）"
                if known["image"] is None:
                    known.pop("image")
                    known["image_note"] = image_note
            return [known], None
        return [], "规格窗没有可核对的规格选项"
    group_names = [normalize(group.name) for group in groups]
    keys_by_group = [list(group.options) for group in groups]
    incomplete = any(group.count is not None and group.count > len(group.options) for group in groups)
    results = []
    unreadable = 0
    for index, keys in enumerate(product(*keys_by_group)):
        if index >= limit:
            incomplete = True
            break
        labels, groups, selected, changed = _choose(phone, labels, groups, keys, group_names)
        if not groups:
            incomplete = True
            break
        if not selected:
            unreadable += 1
            continue
        selection = [(group.name, group.options[key]) for group, key in zip(groups, keys)]
        price = _header_price(labels, groups[0].top) or {}
        if changed:
            for delay in (0.32, 0.5):
                time.sleep(delay)
                labels, groups = _popup(phone.tree(timeout=6))
                if not _confirmed(groups, keys):
                    price = {}
                    break
                updated_price = _header_price(labels, groups[0].top) or {}
                if updated_price == price:
                    break
                price = updated_price
            else:
                price = {}
        after = price.get("price_after_coupon_fen")
        if len(selection) == 1 and after is not None and selection[0][1].inline_price not in (None, after):
            price = {}
        if not price:
            unreadable += 1
        row = {
            "name": " / ".join(option.name for _, option in selection),
            "specifications": {name: option.name for name, option in selection},
            "hot": any(option.hot for _, option in selection),
            **price,
        }
        if output is not None:
            folder = Path(output)
            folder.mkdir(parents=True, exist_ok=True)
            try:
                row["image"], image_note = _selected_header_image(
                    phone, keys, group_names, row["name"], folder / f"规格图-{len(results) + 1:02d}.png",
                )
            except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
                row["image"], image_note = None, f"顶部规格图片未完成核对（{type(error).__name__}）"
            if row["image"] is None:
                row.pop("image")
                row["image_note"] = image_note
            labels, groups = _popup(phone.tree(timeout=5))
        results.append(row)
    notes = []
    if incomplete:
        notes.append("规格数量超过当前可确认范围")
    if unreadable:
        notes.append(f"{unreadable} 个组合的选择或价格未能核实")
    if not results and not notes:
        notes.append("当前界面没有可购买的规格组合")
    return results, "；".join(notes) or None


_ZOOM_STROKE = (
    *((x, 1) for x in range(7, 13)),
    *((12, y) for y in range(2, 7)),
    *((2, y) for y in range(7, 13)),
    *((x, 12) for x in range(3, 8)),
)
_ZOOM_BACKGROUND = ((1, 2), (1, 4), (6, 4), (9, 5), (5, 8), (10, 8), (6, 10), (12, 10))


def _mall_zoom(frame, box):
    """Locate the white expand corners only at the top-left of a verified tile."""
    half = frame.resize((540, 1200)).convert("L")
    left, top = box[:2]
    matches = []
    for x in range(max(0, left // 2 + 2), left // 2 + 16):
        for y in range(top // 2 + 2, top // 2 + 17):
            stroke = [half.getpixel((x + dx, y + dy)) for dx, dy in _ZOOM_STROKE]
            color = sum(stroke) / len(stroke)
            surrounding = sum(half.getpixel((x + dx, y + dy)) for dx, dy in _ZOOM_BACKGROUND) / len(_ZOOM_BACKGROUND)
            if sum(value >= 200 for value in stroke) >= 14 and color - surrounding >= 41:
                matches.append((color - surrounding, (x + 8) * 2, (y + 7) * 2))
    return max(matches)[1:] if matches else None


def capture_images(phone, skus, folder):
    """Match each accessible Mall gallery tile to an exact SKU name."""
    rows = [dict(raw) for raw in skus]
    names = {normalize(row["name"]): row for row in rows
             if len(row.get("specifications", {})) <= 1}
    if not names:
        return rows, "没有可按单选项匹配的规格图"
    tree = phone.tree(timeout=6)
    if tree is None:
        return rows, "规格窗无法读取图片入口"

    controls = {"切换大图", "大图"}
    current_labels = tuple(_labels(tree))
    toggles = [
        label for label in current_labels
        if label.text in controls and 840 <= label.box[0] < label.box[2] <= 1080
        and 650 <= label.box[1] < label.box[3] <= 2070
    ]
    if not any(label.text == "切换小图" for label in current_labels):
        if not toggles or any(abs(label.box[1] - toggles[0].box[1]) > 35 for label in toggles):
            return rows, "未定位到唯一的规格大图入口"
        left, top, right, bottom = min(toggles, key=lambda label: label.text != "切换大图").box
        phone.tap((left + right) // 2, (top + bottom) // 2)
        time.sleep(0.5)
    folder = Path(folder)

    for index, row in enumerate(rows, 1):
        key = normalize(row["name"])
        if names.get(key) is not row or row.get("image"):
            continue
        tree = phone.tree(timeout=5)
        if tree is None:
            break
        current_labels = tuple(_labels(tree))
        tiles = {
            option.box for label in current_labels
            if (option := _option(label, tile=True)) is not None and normalize(option.name) == key
        }
        if len(tiles) != 1:
            continue
        box = tiles.pop()
        frame = phone.screen()
        left, top, right, _ = box
        # In the grid, the SKU's accessible name can cover only the caption.
        # Bind the expand control directly above that same column and caption.
        controls = {
            label.box for label in current_labels if label.text == "查看大图"
            and abs(label.box[0] - left) <= 30
            and label.box[2] <= right
            and 140 <= top - label.box[1] <= 440
        }
        if len(controls) == 1:
            x, y, _, _ = controls.pop()
            point = x + 26, y + 26
            picture_top = y
        elif box[3] - box[1] >= 230:
            point = _mall_zoom(frame, box)
            picture_top = top
        else:
            continue
        if point is None:
            continue
        phone.tap(*point)
        time.sleep(0.55)
        preview = images.preview_crop(phone.screen())
        if preview is None:
            time.sleep(0.35)
            preview = images.preview_crop(phone.screen())
        if preview is None:
            continue
        gallery_tree = phone.tree(timeout=5)
        captured = images._preview_sku_label(gallery_tree, android, (row["name"],))
        thumbnail = frame.crop((left + 6, picture_top + 6, right - 6, picture_top + right - left - 6))
        if captured or images._thumb_match(thumbnail, preview) < 18:
            row["image"] = images._save(
                preview, folder / f"规格图-{index:02d}.png", "抖音商城规格大图预览裁切",
            )
        phone.tap(*images.PREVIEW_BACK)
        time.sleep(0.3)
    missing = sum(not row.get("image") for row in rows)
    return rows, f"{missing} 款规格还没有核对对应图片" if missing else None
