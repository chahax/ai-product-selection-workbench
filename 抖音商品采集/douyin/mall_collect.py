"""Collect a verified Kaogujia page through the standalone Douyin Mall scanner."""

from __future__ import annotations

import argparse
import difflib
import json
import re
import subprocess
import time
from datetime import datetime
from pathlib import Path

import zxingcpp
from PIL import Image

from . import android, images, mall, mall_specs, search
from .collect import build_phone_class, frozen_source, parse_rows, resume_rows, write_json
from .qr_transfer import DeviceAlbum


SCAN = "ScanCommodityActivity"
PICKER = "StubTranslucentActivity"
HOME = ("SplashActivity", "MainActivity")
ROUTE = "mall_kaogujia_qr"
BLOCKERS = {"安全验证", "身份验证", "请完成验证", "登录后继续", "请登录后", "授权照片", "访问照片和视频"}
PAGE_ERRORS = {"网络异常", "商品已下架", "商品不存在", "无法访问页面", "商品暂不可购买"}
SPEC_ENTRY = re.compile(r"选择规格|查看规格|共\s*\d+\s*款|共\s*\d+\s*种[^。；！？\n]{0,18}可选|已选[:：][^。；！？\n]{1,70}")
SKU_COUNT = re.compile(r"共\s*([1-9][0-9]{0,3})\s*款\s*[>›〉＞]?\s*$")
# This item was independently verified in the Mall with a single visible SKU panel.
VERIFIED_CART_SPEC_ENTRY = {"3689530959402762311"}
ERRORS = (OSError, ValueError, RuntimeError, subprocess.SubprocessError)


class Intervention(RuntimeError):
    pass


class MallPhone(build_phone_class(android)):
    def tree(self, timeout=8):
        try:
            self.run("shell", "rm", "-f", self.remote_ui, timeout=3)
            self.run("shell", "uiautomator", "dump", "--compressed", self.remote_ui, timeout=timeout)
            raw = self.run("exec-out", "cat", self.remote_ui)
            return android.ElementTree.fromstring(raw)
        except (subprocess.SubprocessError, android.ElementTree.ParseError):
            return None

    def swipe(self, x1, y1, x2, y2, duration=380):
        self.run("shell", "input", "swipe", *(str(n) for n in (x1, y1, x2, y2, duration)))


def icon(frame, name, xs, ys, limit=18):
    with Image.open(android.REFERENCES / f"mall_{name}.png") as ref:
        return images._template(ref, frame, xs, ys, limit)


def small_screen(phone):
    frame = phone.screen()
    if frame.size != images.SCREEN_SIZE:
        raise Intervention("当前手机截图尺寸与已验证的商城界面不一致")
    return frame.resize(images.SMALL_SIZE)


def tap_icon(phone, match):
    if match is None:
        return False
    phone.tap(match[0] * 2, match[1] * 2)
    return True


def guarded_words(tree):
    words = android.scan_labels(tree) if tree is not None else []
    blocker = next((word for word in words if word in BLOCKERS), None)
    if blocker:
        raise Intervention(f"商城要求处理「{blocker}」")
    return words


def read_screen(phone):
    try:
        return search.screenshot(phone, android, None)
    except search.AppIntervention as exc:
        raise Intervention(str(exc)) from exc
    except (subprocess.SubprocessError, OSError, ValueError):
        return small_screen(phone), []


def screen_title_matches(lines, title):
    if search.full_ocr_match(lines, title):
        return True
    candidates = sorted(
        (line for line in lines if 430 <= line.y <= 1040 and line.x < 315),
        key=lambda line: (line.y, line.x),
    )
    for index, first in enumerate(candidates):
        nearby = [line.key for line in candidates[index + 1:index + 4]
                  if 8 <= line.y - first.y <= 62 and abs(line.x - first.x) <= 220]
        for length in range(len(nearby) + 1):
            text = first.key + "".join(nearby[:length])
            match = difflib.SequenceMatcher(None, title, text, autojunk=False)
            if title in text or (len(title) >= 18 and match.ratio() >= 0.92
                                 and match.find_longest_match().size >= 0.70 * len(title)):
                return True
    return False


def open_album(phone):
    phone.open_douyin()
    if PICKER in phone.top():
        # The picker does not refresh when an image is removed while it stays open.
        phone.back()
        android.wait_for(lambda: PICKER not in phone.top(), 4, "商城相册未能退出")
    for _ in range(8):
        activity = phone.top()
        if not phone.is_app_activity(activity):
            raise Intervention("商城被系统页面遮挡，请检查手机")
        frame = small_screen(phone)
        if PICKER in activity:
            return
        if SCAN in activity:
            if tap_icon(phone, icon(frame, "scan_album", range(402, 426), range(884, 913))):
                time.sleep(0.45)
                continue
            if tap_icon(phone, icon(frame, "scan_mode", range(429, 447), range(69, 81))):
                time.sleep(0.45)
                continue
            guarded_words(phone.tree(timeout=4))
            time.sleep(0.35)
            continue
        if phone.is_product_activity(activity) or "BulletContainer" in activity:
            phone.back()
            time.sleep(0.35)
            continue
        if any(marker in activity for marker in HOME):
            camera = icon(frame, "home_camera", range(386, 407), range(73, 157), 15)
            if tap_icon(phone, camera):
                time.sleep(0.45)
                continue
            guarded_words(phone.tree(timeout=4))
            time.sleep(0.45)
            continue
        guarded_words(phone.tree(timeout=4))
        raise Intervention("商城出现未识别的页面，批次已保存")
    raise RuntimeError("没有进入商城扫码相册")


def choose_qr(phone, product_id):
    for _ in range(4):
        if PICKER not in phone.top():
            raise RuntimeError("商城照片列表意外关闭")
        frame = phone.screen()
        if frame.size != images.SCREEN_SIZE:
            raise Intervention("当前手机截图尺寸与已验证的商城界面不一致")
        matches = []
        for index in range(12):
            row, column = divmod(index, 4)
            x, y = column * 270, 450 + row * 270
            tile = frame.crop((x, y, x + 270, y + 270))
            codes = zxingcpp.read_barcodes(tile, formats=zxingcpp.BarcodeFormat.QRCode)
            if len(codes) == 1 and android.qr_product_id(codes[0].text) == product_id:
                matches.append((x, y, tile))
        if len(matches) == 1:
            x, y, tile = matches[0]
            tile.save(phone.evidence_path / "相册二维码核对.png")
            phone.tap(x + 135, y + 135)
            return
        if len(matches) > 1:
            raise RuntimeError("相册内同时出现多个同商品二维码，未选择")
        time.sleep(0.55)
    raise RuntimeError("相册里未解码到本件商品二维码")


def verify_product(phone, item):
    product_id = item["product_id"]
    title_key = android.normalize(item["title"])
    shop_key = android.normalize(item["shop"])
    deadline = time.monotonic() + 26
    same_title = None
    while time.monotonic() < deadline:
        activity = phone.top()
        if activity and not phone.is_app_activity(activity):
            raise Intervention("扫码离开了抖音商城，请检查手机")
        if not phone.is_product_activity(activity):
            if PICKER not in activity and SCAN not in activity:
                words = guarded_words(phone.tree(timeout=4))
                error = next((word for word in words if word in PAGE_ERRORS), None)
                if error:
                    raise RuntimeError(error)
            time.sleep(0.45)
            continue
        tree = phone.tree(timeout=3)
        words = guarded_words(tree)
        error = next((word for word in words if word in PAGE_ERRORS), None)
        if error:
            raise RuntimeError(error)
        title = next((word for word in words if title_key and android.normalize(word) == title_key), None)
        shop = next((word for word in words if shop_key and (
            android.normalize(word) == shop_key
            or android.normalize(word) in (f"进入{shop_key}", f"进店{shop_key}", f"{shop_key}进店")
        )), None)
        by_id = search.active_product_id(phone, android, product_id)
        if len(words) >= 6 and (by_id or (title and (shop or not shop_key))):
            return tree, [], title or android.title_match(item["title"], words), shop, (
                "product_id" if by_id else (
                    "source_qr_and_exact_title_shop" if shop else "source_qr_and_exact_title"
                )
            )
        # The shop card can be below the first viewport. This route already matched the
        # exact product ID in the selected QR; require the loaded title in two snapshots.
        if len(words) >= 6 and title:
            if same_title == title:
                return tree, [], title, None, "source_qr_and_exact_title"
            same_title = title
        frame, lines = read_screen(phone)
        screen_error = next((line.text for line in lines if line.key in PAGE_ERRORS), None)
        if screen_error or android.network_error_image(frame.resize(images.SCREEN_SIZE)):
            raise RuntimeError(screen_error or "扫码后商品页网络异常")
        if title_key and lines and screen_title_matches(lines, title_key):
            return tree, lines, item["title"], shop, "source_qr_and_screen_title"
        if by_id and any(line.y > 400 and ("已售" in line.key or "券后价" in line.key) for line in lines):
            return tree, lines, item["title"], shop, "product_id"
        time.sleep(0.4)
    raise RuntimeError("扫码后没有核对到商品详情 ID 或完整标题与店铺")


def save_screen(phone, filename):
    target = phone.evidence_path / filename
    small_screen(phone).save(target)
    return str(target)


def capture_tags(phone, tree, title):
    tags, row = mall.product_tags(tree, title)
    if not row:
        return tags
    y = sum(row) // 2
    for _ in range(4):
        phone.swipe(1008, y, 210, y, 430)
        time.sleep(0.28)
        current = phone.tree(timeout=4)
        if current is None:
            break
        more, _ = mall.product_tags(current, title, row_bounds=row)
        merged = mall.merge_product_tags(tags, more)
        if merged == tags:
            break
        tags = merged
    return tags


def visible_sku_count(tree, lines):
    """Read the number shown at the right end of the product's spec preview."""
    counts = set()
    if tree is not None:
        for node in tree.iter("node"):
            box = images.bounds(node)
            if not box or not (760 <= box[0] < box[2] <= 1080 and 990 <= box[1] < box[3] <= 2150):
                continue
            for label in android.labels(node):
                if match := SKU_COUNT.fullmatch(label):
                    counts.add(int(match[1]))
    if not counts:
        for line in lines:
            if 495 <= line.y <= 1055 and line.x + line.w >= 440:
                if match := SKU_COUNT.search(line.text):
                    counts.add(int(match[1]))
    return next(iter(counts)) if len(counts) == 1 else None


def open_specs(phone, tree, lines, product_id):
    entries = []
    if tree is not None:
        for node in tree.iter("node"):
            if any(SPEC_ENTRY.fullmatch(word) for word in android.labels(node)):
                if images.bounds(node) and node.get("enabled") != "false":
                    entries.append(node)
    if len(entries) == 1 and phone.tap_node(entries[0]):
        pass
    else:
        frame, observed = read_screen(phone) if not lines else (small_screen(phone), lines)
        entries = [
            line for line in observed
            if 160 <= line.y <= 1055 and SPEC_ENTRY.search(re.sub(r"\s+", "", line.text))
        ]
        if not phone.is_product_activity(phone.top()):
            return None
        if len(entries) == 1:
            entry = entries[0]
            phone.tap(round(min(504, entry.x + entry.w - 9) * 2), round((entry.y + entry.h / 2) * 2))
        else:
            bag = icon(frame, "sku_bag", range(224, 249), range(1098, 1122), 12)
            if not tap_icon(phone, bag):
                known = [line for line in observed if line.key == "加购"
                         and 195 <= line.x <= 280 and 1090 <= line.y <= 1155]
                if product_id not in VERIFIED_CART_SPEC_ENTRY or len(known) != 1:
                    return None
                search.tap_line(phone, known[0])
    time.sleep(0.48)
    for _ in range(2):
        if not phone.is_product_activity(phone.top()):
            raise RuntimeError("规格入口打开了其他页面")
        popup = phone.tree(timeout=6)
        guarded_words(popup)
        if mall_specs.popup_open(popup):
            return popup
        time.sleep(0.3)
    return None


def capture(phone, item, row):
    open_album(phone)
    choose_qr(phone, item["product_id"])
    tree, lines, title, shop, identity = verify_product(phone, item)
    row.update(status="verified_product", matched_by=identity, title_seen=title, shop_seen=shop,
               shot=save_screen(phone, "商品页.png"))
    sku_count = visible_sku_count(tree, lines)
    if sku_count is None and not lines:
        _, lines = read_screen(phone)
        sku_count = visible_sku_count(tree, lines)
    if sku_count is not None:
        row.update(sku_count=sku_count, sku_count_source="商品详情规格入口")
    words = guarded_words(tree) or [line.text for line in lines]
    row["page_prices"] = [
        {"price_before_coupon_fen": before, "price_after_coupon_fen": after, "is_from": starting}
        for before, after, starting in android.pairs(words)
    ]
    row["product_tags"] = capture_tags(phone, tree, title or item["title"])
    if row["product_tags"]:
        row["tags_shot"] = save_screen(phone, "商品标签.png")
    popup = open_specs(phone, tree, lines, item["product_id"])
    if popup is None:
        row["sku_note"] = "未识别到可核对的规格选项"
        return
    row["specs_shot"] = save_screen(phone, "规格弹窗.png")
    skus, note = mall_specs.collect_quotes(phone, popup, output=phone.evidence_path)
    row.update(skus=skus, sku_note=note)
    if not skus:
        return
    row["status"] = "sku_checked"
    missing_images = sum(not sku.get("image") for sku in skus)
    row["sku_image_note"] = f"{missing_images} 款规格未能核对对应图片" if missing_images else None


def collect_row(phone, album, item, index, evidence, seconds):
    folder = evidence / f"{index:02d}-{item['product_id']}"
    folder.mkdir(parents=True, exist_ok=True)
    phone.evidence_path = folder
    row = {
        "index": index, "order": item.get("order", index), "product_id": item["product_id"],
        "kaogujia_key": item.get("kaogujia_key"), "title": item["title"], "shop": item["shop"],
        "tested_at": datetime.now().astimezone().isoformat(timespec="seconds"),
        "directory": str(folder), "status": "not_attempted",
    }
    qr = item.get("_qr_path")
    if qr is None:
        row["reason"] = "该商品未取得可核对的考古加原码"
        return row, False
    receipt = folder / f"传输凭据-{time.time_ns()}.json"
    row["qr_receipt"] = receipt.name
    stop = False
    phone.deadline = time.monotonic() + seconds
    row["status"] = "blocked"
    try:
        transfer = album.push(qr, receipt, item["product_id"])
        if not transfer["media_indexed"]:
            raise RuntimeError("二维码已传输，相册尚未收录")
        row["qr_source"] = "kaogujia_png"
        capture(phone, item, row)
    except Intervention as exc:
        row.update(status="intervention_required", reason=str(exc))
        stop = True
    except KeyboardInterrupt:
        row["reason"] = "本轮运行已中断"
        stop = True
    except ERRORS as exc:
        row["reason"] = str(exc) if isinstance(exc, (RuntimeError, ValueError)) else type(exc).__name__
    finally:
        phone.deadline = None
        if receipt.is_file():
            try:
                cleanup = album.remove(receipt)
                row["phone_qr_cleanup"] = cleanup
                if not cleanup["file_removed"] or cleanup["media_entry_removed"] is not True:
                    stop = True
            except ERRORS as exc:
                row["phone_qr_cleanup"] = {"file_removed": False, "media_entry_removed": None,
                                           "reason": type(exc).__name__}
                stop = True
    return row, stop


def recover_qr_cleanup(album, evidence, recorded, output):
    for index, row in sorted(recorded.items()):
        cleanup = row.get("phone_qr_cleanup")
        if not cleanup or cleanup.get("file_removed") is True and cleanup.get("media_entry_removed") is True:
            continue
        folder = evidence / f"{index:02d}-{row['product_id']}"
        receipt_name = row.get("qr_receipt")
        if isinstance(receipt_name, str) and re.fullmatch(r"传输凭据-\d+\.json", receipt_name):
            receipt = folder / receipt_name
        else:
            device_file = cleanup.get("device_file")
            matches = [
                path for path in folder.glob("传输凭据-*.json")
                if device_file and device_file == "/sdcard/Pictures/" + json.loads(
                    path.read_text(encoding="utf-8")
                ).get("filename", "")
            ]
            if len(matches) != 1:
                raise RuntimeError(f"第 {index} 项的二维码清理凭据无法核对")
            receipt = matches[0]
        if not receipt.is_file():
            raise RuntimeError(f"第 {index} 项的二维码清理凭据缺失")
        row["phone_qr_cleanup"] = cleanup = album.remove(receipt)
        write_json(output, [recorded[i] for i in sorted(recorded)])
        if not cleanup["file_removed"] or cleanup["media_entry_removed"] is not True:
            raise RuntimeError(f"第 {index} 项的二维码清理待确认")
        print(f"第 {index} 项的手机二维码清理已核对", flush=True)


def main(argv=None):
    parser = argparse.ArgumentParser(description="自动用抖音商城真机扫码采集考古加整页商品")
    parser.add_argument("page", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--adb", type=Path)
    parser.add_argument("--resume", action="store_true")
    parser.add_argument("--start", type=int, default=1)
    parser.add_argument("--end", type=int)
    parser.add_argument("--seconds-per-item", type=int, default=180)
    args = parser.parse_args(argv)
    output = args.output.resolve()
    identity_path = output.with_name(output.stem + "-source.json")
    evidence = output.with_name(output.stem + "-evidence")
    phone = None
    try:
        rows = parse_rows(args.page.resolve())
        end = args.end or len(rows)
        if not 1 <= args.start <= end <= len(rows) or args.seconds_per_item < 20:
            raise ValueError("请核对开始、结束行号及单件采集时间")
        # QR files can be refreshed independently. DeviceAlbum verifies each QR before scanning.
        source_rows = [{key: value for key, value in row.items() if key != "qr_file"} for row in rows]
        identity = frozen_source(source_rows, ROUTE)
        recorded = resume_rows(output, identity_path, identity, args.resume)
        adb = args.adb or android.resolve_adb()
        phone = MallPhone(args.serial, evidence, adb_path=adb, app_package=mall.PACKAGE)
        album = DeviceAlbum(adb, args.serial)
        if album.run("get-state").stdout.strip() != b"device":
            raise RuntimeError("手机尚未通过 ADB 授权")
        output.parent.mkdir(parents=True, exist_ok=True)
        if not args.resume:
            write_json(identity_path, identity)
            write_json(output, [])
        else:
            recover_qr_cleanup(album, evidence, recorded, output)
        for index in range(args.start, end + 1):
            previous = recorded.get(index, {})
            cleanup = previous.get("phone_qr_cleanup") or {}
            prior_skus = previous.get("skus") or []
            if previous.get("status") == "complete" or (
                previous.get("status") == "sku_checked" and prior_skus
                and all(sku.get("image") for sku in prior_skus) and not previous.get("sku_note")
            ):
                continue
            row, stop = collect_row(phone, album, rows[index - 1], index, evidence, args.seconds_per_item)
            if prior_skus and not row.get("skus"):
                row = {**previous, "last_retry_status": row["status"],
                       "last_retry_reason": row.get("reason") or row.get("sku_note"),
                       "qr_receipt": row.get("qr_receipt") or previous.get("qr_receipt"),
                       "phone_qr_cleanup": row.get("phone_qr_cleanup", cleanup)}
            recorded[index] = row
            write_json(output, [recorded[i] for i in sorted(recorded)])
            print(f"{index}/{len(rows)} · {row['status']} · {len(row.get('skus', []))} 个规格"
                  f"{' · ' + row['reason'] if row.get('reason') else ''}", flush=True)
            if stop:
                return 2
    except ERRORS as exc:
        print(f"商城采集未完成 · {exc}", flush=True)
        return 1
    finally:
        if phone is not None:
            try:
                phone.run("shell", "rm", "-f", phone.remote_ui, timeout=3)
            except (OSError, subprocess.SubprocessError):
                pass
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
