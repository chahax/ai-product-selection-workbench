from __future__ import annotations

import difflib
import io
import os
import pathlib
import re
import shlex
import shutil
import subprocess
import time
import unicodedata
import uuid
from decimal import Decimal
from urllib.parse import parse_qs, urlsplit
from xml.etree import ElementTree

from PIL import Image, ImageChops

REFERENCES = pathlib.Path(__file__).with_name('references')
PKG = "com.ss.android.ugc.aweme"
PDP = "ProductDetailV2Activity"
SCAN = "ScanNewActivity"
MONEY = r"(?:¥|￥)\s*(\d+(?:\.\d{1,2})?)"
PAIR = re.compile(MONEY + r"\s*(起?)\s*券后价\s*" + MONEY + r"\s*(起?)")
ROW = re.compile(r"^(?:未选择|未选中|已选择|已选中|已选)\s*(.+?)\s*" + MONEY + "$")
NORMALIZE = re.compile(r"[\W_]+")
FAILURE_PROMPTS = ["无法访问页面", "安全验证", "请完成验证", "请登录后", "商品已下架", "商品不存在"]
MENU_BOX = (596, 83, 832, 173)


def wait_for(test, timeout, reason):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        result = test()
        if result:
            return result
        time.sleep(0.45)
    raise RuntimeError(reason)


def normalize(text):
    return NORMALIZE.sub("", unicodedata.normalize("NFKC", text).casefold())


def cents(text):
    return int(Decimal(text) * 100)


def resolve_adb():
    executable = shutil.which("adb")
    if executable:
        return pathlib.Path(executable)
    roots = [os.environ.get("ANDROID_SDK_ROOT"), os.environ.get("ANDROID_HOME")]
    if os.environ.get("LOCALAPPDATA"):
        roots.append(str(pathlib.Path(os.environ["LOCALAPPDATA"]) / "Android" / "Sdk"))
    for sdk in (pathlib.Path(root) for root in roots if root):
        for name in ("adb.exe", "adb"):
            executable = sdk / "platform-tools" / name
            if executable.is_file():
                return executable
    raise RuntimeError("未找到 Android SDK 的 adb")


def resumed_activity(state, activity=None):
    lines = [line.strip() for line in state.splitlines()]
    for prefix in ("topResumedActivity=", "mResumedActivity:", "ResumedActivity:"):
        match = next((line for line in lines if line.startswith(prefix)
                      and "ActivityRecord{" in line and (activity is None or activity in line)), None)
        if match:
            return match
    return ""


class Phone:
    def __init__(self, serial, evidence_path, album_slot=None, adb_path=None, *, app_package=PKG, product_activity=PDP):
        self.evidence_path = evidence_path
        self.album_slot = album_slot
        self.app_package = app_package
        self.product_activity = product_activity
        self.adb = [str(adb_path or resolve_adb()), "-s", serial]
        self.remote_base = "/data/local/tmp/codex-kaogu-" + uuid.uuid4().hex
        self.remote_ui = self.remote_base + ".xml"
        self.remote_qr = self.remote_base + ".png"
        self.menu_ref = Image.open(REFERENCES / "qr_menu_classic.png").convert("L").resize((70, 28))

    def run(self, *args, timeout=8):
        result = subprocess.run(self.adb + list(args), capture_output=True, check=True, timeout=timeout)
        return result.stdout

    def top(self):
        state = self.run("shell", "dumpsys", "activity", "activities").decode("utf-8", "replace")
        return resumed_activity(state)

    def is_app_activity(self, state):
        return re.search(r"(?<![\w.])" + re.escape(self.app_package) + r"/", state) is not None

    def is_product_activity(self, state):
        pattern = r"(?:[/\.])" + re.escape(self.product_activity) + r"(?=[\s}]|$)"
        return self.is_app_activity(state) and re.search(pattern, state) is not None

    def screen(self):
        return Image.open(io.BytesIO(self.run("exec-out", "screencap", "-p"))).convert("RGB")

    def tap(self, x, y):
        self.run("shell", "input", "tap", str(x), str(y))

    def back(self):
        self.run("shell", "input", "keyevent", "KEYCODE_BACK")

    def tree(self, timeout=8):
        try:
            self.run("shell", "uiautomator", "dump", self.remote_ui, timeout=timeout)
            raw = self.run("exec-out", "cat", self.remote_ui)
            return ElementTree.fromstring(raw)
        except (subprocess.TimeoutExpired, subprocess.CalledProcessError, ElementTree.ParseError):
            return None

    def write_verified(self, payload, scratch):
        scratch.write_bytes(payload)
        if self.album_slot is None:
            raise RuntimeError("扫码路线需要设置专用相册文件")

        def inode_path():
            return self.run("shell", "stat", "-c", "%i", self.album_slot).strip()

        old_inode = inode_path()
        self.run("push", str(scratch), self.remote_qr, timeout=15)
        self.run("shell", "cat " + shlex.quote(self.remote_qr) + " > " + shlex.quote(self.album_slot))
        if old_inode != inode_path() or self.run("exec-out", "cat", self.album_slot) != payload:
            raise RuntimeError("二维码照片没有原地更新成功")

    def menu_visible(self):
        image = self.screen().convert("L").crop(MENU_BOX).resize(self.menu_ref.size)
        diff = ImageChops.difference(image, self.menu_ref)
        return sum(n * v for v, n in enumerate(diff.histogram())) / (image.width * image.height) < 20

    def scanner_menu_point(self):
        current = self.screen().resize((540, 1200)).convert("L")
        candidate = current.crop((35, 100, 135, 179))
        new_style = Image.open(REFERENCES / "qr_menu_current.png").convert("L")
        if mean_diff(candidate, new_style) <= 19:
            return (165, 280)
        if self.menu_visible():
            return (712, 125)
        return None

    def live_scanner(self):
        state = self.top()
        return self.is_app_activity(state) and SCAN in state

    def looks_album(self, image):
        return all(c > 222 for c in image.getpixel((730, 650))) and all(c > 220 for c in image.getpixel((952, 350)))

    def album_qr(self):
        image = self.screen()
        if not self.looks_album(image):
            return False
        tile = image.crop((390, 327, 690, 625)).convert("L")
        pixels = tile.histogram()
        area = tile.width * tile.height
        dark = sum(pixels[:185]) / area
        white = sum(pixels[215:]) / area
        return 0.11 < dark < 0.78 and white > 0.15

    def open_album(self):
        for _ in range(3):
            if self.album_qr():
                return
            screen = self.screen()
            if self.live_scanner() and not self.looks_album(screen) and max(screen.getpixel((640, 1750))) < 90:
                self.tap(853, 1975)
            time.sleep(1.35)
        wait_for(self.album_qr, 2, "未识别到固定相册二维码")

    def open_scanner(self):
        menu_requested = False
        for _ in range(6):
            top = self.top()
            if not self.is_app_activity(top):
                raise RuntimeError("抖音不在前台或出现系统权限弹窗")
            if self.live_scanner():
                self.open_album()
                return
            point = self.scanner_menu_point()
            if point:
                self.tap(*point)
                wait_for(self.live_scanner, 5, "未进入扫一扫")
                time.sleep(1.25)
                self.open_album()
                return
            if self.is_product_activity(top) or "BulletContainer" in top:
                self.back()
                time.sleep(0.6)
                continue
            if any(c in top for c in ("MainActivity", "SplashActivity")):
                if not menu_requested:
                    self.tap(70, 120)
                    menu_requested = True
                time.sleep(0.6)
                continue
            raise RuntimeError("无法判断当前抖音页面，未继续点击")
        raise RuntimeError("未定位到抖音扫一扫菜单")

    def scan(self, expected):
        self.open_scanner()
        self.tap(540, 480)
        time.sleep(1.0)
        start = time.monotonic()
        deadline = start + 21
        green_clicked = False
        foreign_since = None
        while time.monotonic() < deadline:
            active = self.top()
            if self.is_product_activity(active):
                break
            if not self.is_app_activity(active):
                if active:
                    foreign_since = foreign_since or time.monotonic()
                    if time.monotonic() - foreign_since > 4:
                        self.screen().resize((540, 1200)).save(self.evidence_path / f"{expected}-external-screen.png")
                        raise RuntimeError("\u626b\u7801\u540e\u79bb\u5f00\u6296\u97f3")
                time.sleep(0.6)
                continue
            foreign_since = None
            if not green_clicked and time.monotonic() - start > 1.2:
                frame = self.screen()
                hit = recognized_qr_button(frame)
                if hit:
                    visible = frame.resize((540, 1200))
                    visible.save(self.evidence_path / f"{expected}-green-dot.png")
                    self.tap(*hit)
                    green_clicked = True
                    deadline = max(deadline,time.monotonic() + 14)
            time.sleep(0.6)
        else:
            self.screen().resize((540, 1200)).save(self.evidence_path / f"{expected}-scan-timeout.png")
            raise RuntimeError("\u626b\u7801\u540e\u6ca1\u6709\u8fdb\u5165\u5546\u54c1\u8be6\u60c5\u9875")
        full = self.run("shell", "dumpsys", "activity", "-a", "activities").decode("utf-8", "replace")
        top = resumed_activity(full)
        if not self.is_product_activity(top):
            return False
        record = re.search(r"ActivityRecord\{([a-f0-9]+) ", top)
        if record:
            needle = "ActivityRecord{" + record.group(1)
            lines = full.splitlines()
            for idx, line in enumerate(lines):
                if "* Hist" in line and needle in line:
                    next_record = next((j for j in range(idx + 1, len(lines)) if re.search(r"\* Hist\s+#", lines[j])), len(lines))
                    if expected in "\n".join(lines[idx:next_record]):
                        return True
        return False

    def sku_popup(self, tree):
        buttons = []
        for node in tree.iter("node"):
            for label in labels(node):
                if re.fullmatch(r"共\s*\d+\s*款", label):
                    buttons.append(node)
                    break
        if len(buttons) == 1 and self.tap_node(buttons[0]):
            return True
        return self.visual_sku_popup()

    def visual_sku_popup(self):
        if not self.is_product_activity(self.top()):
            return False
        screen = self.screen().convert("L")
        glyph = Image.open(REFERENCES / "sku_count_variant_glyph.png").convert("L")
        prefix = Image.open(REFERENCES / "sku_count_common_glyph.png").convert("L")
        best = (99, 0, 0)
        for x in range(947, 966, 2):
            for y in range(500, 1811, 3):
                patch = screen.crop((x, y, x + glyph.width, y + glyph.height))
                error = mean_diff(patch, glyph)
                if error >= best[0] or error > 26:
                    continue
                left = [screen.crop((x - gap, y, x - gap + prefix.width, y + prefix.height)) for gap in (54, 55, 56, 72, 73)]
                if min(mean_diff(candidate, prefix) for candidate in left) < 27:
                    best = (error, x, y)
        if best[1]:
            self.tap(best[1] + 13, best[2] + 19)
            return True
        return False

    def tap_node(self, node):
        coords = re.findall(r"\d+", node.get("bounds", ""))
        if len(coords) != 4:
            return False
        x1, y1, x2, y2 = map(int, coords)
        if not (0 <= x1 < x2 <= 1080 and 0 <= y1 < y2 <= 2140):
            return False
        self.tap((x1 + x2) // 2, (y1 + y2) // 2)
        return True


GREEN_REF = REFERENCES / "qr_confirmation_hint.png"
NETWORK_REF = REFERENCES / "network_error_panel.png"


def recognized_qr_button(image):
    reference = Image.open(GREEN_REF).convert("L")
    small = image.convert("RGB").resize((540, 1200))
    pose = small.convert("L")
    text_box = (145, 910, 395, 955)
    # Require the characteristic black-background instruction below the recognized code.
    if mean_diff(pose.crop(text_box), reference) > 12:
        return None
    rgb = small.load()
    green = [(x, y) for y in range(440, 675) for x in range(170, 370)
             if (pixel := rgb[x, y])[1] >= 110 and pixel[1] >= pixel[0] + 65 and pixel[1] >= pixel[2] + 30]
    if len(green) < 200:
        return None
    x_lo = min(x for x, _ in green)
    x_hi = max(x for x, _ in green)
    y_lo = min(y for _, y in green)
    y_hi = max(y for _, y in green)
    if not (20 <= x_hi - x_lo <= 63 and 16 <= y_hi - y_lo <= 57):
        return None
    return (x_lo + x_hi, y_lo + y_hi)


def network_error_image(image):
    screen = image.convert("L").resize((540, 1200))
    reference = Image.open(NETWORK_REF).convert("L")
    prompt = (152, 466, 386, 744)
    return mean_diff(screen.crop(prompt), reference) <= 12


def mean_diff(left, right):
    hist = ImageChops.difference(left, right).histogram()
    return sum(a * b for a, b in enumerate(hist)) / (left.width * left.height)


def labels(node):
    return dict.fromkeys(
        unicodedata.normalize("NFKC", node.get(key, "")).strip()
        for key in ("text", "content-desc") if node.get(key)
    )


def scan_labels(tree):
    return list(dict.fromkeys(label for node in tree.iter("node") for label in labels(node)))


def title_match(expected, candidates):
    start = normalize(expected)
    if len(start) < 7:
        return None
    best = (0, None)
    for candidate in candidates:
        key = normalize(candidate)
        if len(key) < 8:
            continue
        if start == key or start in key or key in start and len(key) / len(start) >= 0.8:
            return candidate
        score = difflib.SequenceMatcher(None, start, key, autojunk=False).ratio()
        if score > best[0]:
            best = (score, candidate)
    return best[1] if best[0] >= 0.76 else None


def pairs(values):
    return list(dict.fromkeys(
        (cents(m[1]), cents(m[3]), bool(m[2] or m[4]))
        for label in values for m in PAIR.finditer(label)
    ))


def footer_coupon(tree):
    footer = []
    for node in tree.iter("node"):
        bounds = re.findall(r"\d+", node.get("bounds", ""))
        if len(bounds) == 4 and int(bounds[0]) > 540 and int(bounds[1]) > 2150:
            footer.extend(labels(node))
    price = [cents(m[1]) for title in footer if (m := re.fullmatch(MONEY, title))]
    if "券后价" in footer and len(set(price)) == 1:
        return price[0]
    return None


def row_nodes(tree):
    options = {}
    for node in tree.iter("node"):
        for label in labels(node):
            match = ROW.fullmatch(label)
            if match:
                name = match[1].strip()
                hot = any("热销" in labels(ch) for ch in node.iter("node"))
                options.setdefault(name, (node, cents(match[2]), hot))
    return options


def quotes(phone, first):
    results = []
    start = row_nodes(first)
    if not start:
        from . import mall
        if phone.app_package == mall.PACKAGE:
            quote = mall.single_option_quote(first)
            if quote is not None:
                return [quote], None
        return [], "规格窗没有可识别的逐款报价"
    current = first
    for index, (name, (_, row_price, hot)) in enumerate(start.items()):
        if index >= 12:
            return results, "超过单商品逐款核价上限"
        if current is None or name not in row_nodes(current):
            break
        node = row_nodes(current)[name][0]
        if not phone.tap_node(node):
            continue
        answer = None
        for attempt in range(2):
            current = phone.tree(timeout=6)
            if current is None:
                continue
            words = scan_labels(current)
            explicit = any(word.startswith("已选 ") and normalize(name) in normalize(word) for word in words)
            matches = [(before, after) for before, after, is_from in pairs(words) if after == row_price and not is_from]
            if explicit and len(matches) == 1:
                answer = matches[0][0]
                break
        results.append({
            "name": name, "price_before_coupon_fen": answer,
            "price_after_coupon_fen": row_price, "hot": hot,
        })
    if len(results) != len(start) or not all(x["price_before_coupon_fen"] is not None for x in results):
        return results, "部分款式未确认券前价"
    return results, None


def qr_product_id(url):
    parsed = urlsplit(url)
    if (parsed.scheme, parsed.netloc, parsed.path) != (
        "https", "haohuo.jinritemai.com", "/ecommerce/trade/detail/index.html"
    ):
        return None
    ids = parse_qs(parsed.query).get("id", [])
    return ids[0] if len(ids) == 1 and re.fullmatch(r"\d{10,25}", ids[0]) else None


def qr_payload(item, expected_id):
    import zxingcpp

    staged = item.get("_qr_path")
    if staged is not None:
        if not staged.is_file() or staged.stat().st_size > 8 * 1024 * 1024:
            raise ValueError("商品二维码 PNG 不存在或文件过大")
        payload = staged.read_bytes()
        with Image.open(io.BytesIO(payload)) as image:
            if image.format != "PNG":
                raise ValueError("考古加二维码文件必须是 PNG")
            codes = zxingcpp.read_barcodes(image, formats=zxingcpp.BarcodeFormat.QRCode)
        if len(codes) != 1 or qr_product_id(codes[0].text) != expected_id:
            raise ValueError("考古加原始二维码解码的商品 ID 与本行不一致")
        return payload, "kaogujia_png"

    url = item["url"]
    qr = zxingcpp.create_barcode(url, zxingcpp.BarcodeFormat.QRCode)
    qr_file = io.BytesIO()
    qr_image = Image.fromarray(zxingcpp.write_barcode_to_image(qr, scale=9))
    if zxingcpp.read_barcode(qr_image).text != url:
        raise RuntimeError("二维码内容校验失败")
    qr_image.save(qr_file, format="PNG")
    return qr_file.getvalue(), "generated_from_product_url"


def product(phone, item, qr_scratch, index, page_only):
    expected = item.get("title", "")
    id_value = item.get("product_id") or qr_product_id(item.get("url", ""))
    if not id_value or (item.get("url") and qr_product_id(item["url"]) != id_value):
        raise ValueError("没有与来源商品 ID 一致的抖音商品链接")
    payload, qr_source = qr_payload(item, id_value)
    phone.write_verified(payload, qr_scratch)
    exact = phone.scan(id_value)
    tree = None
    found = None
    values = []
    refreshed = False
    image_network_failure = False
    app_error = None
    for _ in range(4):
        tree = phone.tree(timeout=8)
        values = scan_labels(tree) if tree is not None else []
        image_network_failure = network_error_image(phone.screen())
        if "\u7f51\u7edc\u5f02\u5e38" in values or image_network_failure:
            if not refreshed:
                refresh = [node for node in tree.iter("node") if "\u5237\u65b0" in labels(node)] if tree is not None else []
                if len(refresh) == 1 and phone.tap_node(refresh[0]):
                    refreshed = True
                elif image_network_failure:
                    phone.tap(540, 1405)
                    refreshed = True
                if refreshed:
                    time.sleep(3.5)
                    continue
            app_error = "douyin_product_page_network_error"
            break
        app_error = next((v for v in values if v in FAILURE_PROMPTS), None)
        if app_error:
            break
        found = title_match(expected, values)
        if found or exact and len(values) >= 8:
            break
        time.sleep(1.5)
    shot = phone.evidence_path / f"{index:02d}-{id_value}.png"
    screenshot = phone.screen()
    screenshot.thumbnail((540, 1200))
    screenshot.save(shot)
    result = {"product_id": id_value, "title_expected": expected, "shot": str(shot),
              "qr_source": qr_source}
    if app_error:
        return {**result, "status": app_error, "refreshed": refreshed}
    if tree is None:
        return {**result, "status": "opened_ui_unreadable" if exact else "opened_unverified"}
    if not exact and found is None:
        return {**result, "status": "opened_unverified"}
    result["status"] = "verified_product"
    result["title_seen"] = found
    shop = normalize(item.get("shop", ""))
    if shop:
        result["shop_seen"] = next((value for value in values if shop in normalize(value)), None)
    result["matched_by"] = "product_id" if exact else "title"
    result["page_prices"] = [
        {"price_before_coupon_fen": before, "price_after_coupon_fen": after, "is_from": start}
        for before, after, start in pairs(values)
    ]
    if not result["page_prices"]:
        footer = footer_coupon(tree)
        if footer is not None:
            result["page_prices"] = [{"price_before_coupon_fen": None, "price_after_coupon_fen": footer}]
    if page_only:
        return result
    if phone.sku_popup(tree):
        popup = phone.tree()
        if popup is not None:
            result["skus"], result["sku_note"] = quotes(phone, popup)
        else:
            result["sku_note"] = "规格窗读取超时"
        popup_shot = phone.evidence_path / f"{index:02d}-{id_value}-specs.png"
        screenshot = phone.screen()
        screenshot.thumbnail((540, 1200))
        screenshot.save(popup_shot)
        result["specs_shot"] = str(popup_shot)
    else:
        result["sku_note"] = "当前商品界面未定位到规格入口"
    return result

