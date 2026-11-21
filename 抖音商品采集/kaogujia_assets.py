from __future__ import annotations

import argparse
import base64
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime
import json
from pathlib import Path
import re
import sys
import threading
import time
import unicodedata
from urllib.parse import urlsplit, urlunsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener, urlopen

import websocket

from kaogujia_page import ChromeConnection, PRODUCT_LIST


MARKET = "https://www.kaogujia.com"
OVERVIEW = "/productDetails/productOverview/"
RELATED = "/productDetails/linkVideo/"
RELATED_API = "/api/sku/video/list"
DOUYIN = "https://www.douyin.com"
MAX_IMAGE_BYTES = 7_000_000
COMPLETED_RELATED_STATUSES = {"empty_current_period", "metadata_only", "no_related_tab", "public_links",
                              "restricted", "saved_one_video"}
DETAIL_JS = r"""
(() => {
  const card = document.querySelector('.detail-content-left .left-box');
  const title = card?.querySelector('.title-text, .title-text-none');
  const image = card?.querySelector('.box-flex-s > .img-box img');
  return {
    path: location.pathname, title: title?.textContent.trim() || '',
    image: image?.currentSrc || '', width: image?.naturalWidth || 0, height: image?.naturalHeight || 0,
    videos: [...card?.querySelectorAll('video') || []].map(v => v.currentSrc || v.src || v.querySelector('source')?.src),
    related: [...document.querySelectorAll('.el-tabs__item')].some(e => e.textContent.trim() === '关联视频'),
  };
})()
"""
RELATED_JS = r"""
(() => {
  const title = document.querySelector('.detail-content-left .left-box .title-text, .detail-content-left .left-box .title-text-none');
  const visible = e => e.getBoundingClientRect().width > 0 && e.getBoundingClientRect().height > 0;
  const table = document.querySelector('tr.el-table__row .item-video')?.closest('tr');
  const rows = table?.__vueParentComponent?.props?.store?.states?.data?.value;
  return {
    path: location.pathname, title: title?.textContent.trim() || '',
    items: Array.isArray(rows) ? rows.slice(0, 10).map(v => ({title: v.title, url: v.url, deleted: v.is_deleted})) : null,
    empty: [...document.querySelectorAll('.el-table__empty-text')].some(e => visible(e) && e.innerText.includes('暂无数据')),
    restricted: [...document.querySelectorAll('.k-empty, .no-auth-container, .no-data-auth, [class*="permission"]')]
      .some(e => visible(e) && /权限|升级|开通.*会员/.test(e.innerText)),
    loading: [...document.querySelectorAll('.el-loading-mask')].some(visible),
  };
})()
"""
PLAYER_JS = r"""
(() => {
  const visible = e => e.getBoundingClientRect().width > 0 && e.getBoundingClientRect().height > 0;
  const text = document.body?.innerText || '';
  const challenge = [...document.querySelectorAll('.captcha_verify_container,.verify-bar-close,[id*="captcha"]')].some(visible);
  const films = [...document.querySelectorAll('video')].flatMap(v => {
    const url = v.currentSrc || v.src || '';
    return /(?:douyinvod\.com|douyincdn\.com|bytevod\.com)\//i.test(url) ? [{url, ready: v.readyState}] : [];
  });
  return {
    host: location.hostname, path: location.pathname, films,
    verification: challenge || /请完成安全验证|完成验证后继续/.test(text),
    unavailable: /视频已下架|作品已删除|视频已失效|暂时无法播放/.test(text),
  };
})()
"""


class AssetBrowser(ChromeConnection):
    def __init__(self, endpoint):
        super().__init__(endpoint, {}, {})
        self.target = None
        self.session = None
        self.frame = None
        self.related_status = None
        self.media = {}
        try:
            self.target = self.call("Target.createTarget", {"url": "about:blank", "background": True})["targetId"]
            self.session = self.call("Target.attachToTarget", {"targetId": self.target, "flatten": True})["sessionId"]
            self.action("Page.enable")
            self.action("Network.enable", {"maxTotalBufferSize": 30_000_000, "maxResourceBufferSize": 15_000_000})
        except (RuntimeError, OSError, websocket.WebSocketException):
            self.close()
            raise

    def observe(self, event):
        if event.get("method") != "Network.responseReceived" or event.get("sessionId") != self.session:
            return
        data = event["params"]
        response = data["response"]
        url = response["url"]
        mime = response["mimeType"]
        status = int(response["status"])
        if mime.startswith(("image/", "video/")) or data.get("type") in ("Image", "Media"):
            self.media[url] = (data["requestId"], status)
        address = urlsplit(url)
        if address.hostname == "service.kaogujia.com" and address.path == RELATED_API:
            self.related_status = status

    def action(self, method, params=None):
        return self.call(method, params, self.session)

    def navigate(self, url):
        self.media.clear()
        self.related_status = None
        self.frame = self.action("Page.navigate", {"url": url}).get("frameId")

    def evaluate(self, expression):
        response = self.action("Runtime.evaluate", {"expression": expression, "returnByValue": True})
        return response.get("result", {}).get("value") or {}

    def bytes_from_page(self, url, *, complete_response=False):
        record = self.media.get(url)
        if record and record[1] == 200:
            try:
                response = self.action("Network.getResponseBody", {"requestId": record[0]})
                if response.get("base64Encoded"):
                    return base64.b64decode(response["body"], validate=True)
            except (RuntimeError, ValueError):
                pass
        if self.frame and not complete_response:
            try:
                response = self.action("Page.getResourceContent", {"frameId": self.frame, "url": url})
                if response.get("base64Encoded"):
                    return base64.b64decode(response["content"], validate=True)
            except (RuntimeError, ValueError):
                pass
        return b""

    def close(self):
        if self.target:
            try:
                self.call("Target.closeTarget", {"targetId": self.target})
            except (RuntimeError, OSError, websocket.WebSocketException):
                pass
        super().close()


def poll(browser, expression, done, seconds):
    deadline = time.monotonic() + seconds
    state = {}
    while time.monotonic() < deadline:
        state = browser.evaluate(expression)
        if done(state):
            break
        time.sleep(0.3)
    return state


def normalize_title(value):
    return re.sub(r"[\W_]+", "", unicodedata.normalize("NFKC", value or "").casefold())


def source_ref(value):
    address = urlsplit(value)
    if address.scheme == "https" and address.hostname and not (address.username or address.password):
        return urlunsplit(address._replace(query="", fragment=""))
    return None


class DirectImageOnly(HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


def read_public_image(url):
    address = urlsplit(url)
    host = address.hostname or ""
    if address.scheme != "https" or address.netloc != host or address.query or address.fragment:
        return b""
    allowed_hosts = (".kaogujia.com", ".ecombdimg.com", ".douyinpic.com", ".byteimg.com")
    if not host.endswith(allowed_hosts) or "-sign" in host:
        return b""
    try:
        opener = build_opener(DirectImageOnly())
        with opener.open(Request(url, headers={"User-Agent": "Mozilla/5.0"}), timeout=4) as response:
            if response.status == 200 and response.headers.get_content_type().startswith("image/"):
                return response.read(MAX_IMAGE_BYTES + 1)
    except OSError:
        pass
    return b""


def share_url(value):
    if not isinstance(value, str):
        return None
    address = urlsplit(value)
    if address.scheme != "https" or address.netloc != "www.douyin.com":
        return None
    match = re.fullmatch(r"/(?:share/)?video/(\d{12,25})/?", address.path)
    return f"{DOUYIN}/share/video/{match[1]}" if match else None


def image_extension(data):
    if data.startswith(b"\xff\xd8\xff"):
        return ".jpg"
    if data.startswith(b"\x89PNG\r\n\x1a\n"):
        return ".png"
    if data.startswith(b"RIFF") and data[8:12] == b"WEBP":
        return ".webp"
    if data.startswith((b"GIF87a", b"GIF89a")):
        return ".gif"
    if data[4:12] in (b"ftypavif", b"ftypavis"):
        return ".avif"
    return None


def video_extension(data):
    if data[4:8] == b"ftyp" and b"moov" in data and b"mdat" in data:
        return ".mp4"
    if data.startswith(b"\x1a\x45\xdf\xa3") and len(data) > 64:
        return ".webm"
    return None


def save_bytes(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    writing = path.with_suffix(path.suffix + ".writing")
    writing.write_bytes(data)
    writing.replace(path)
    return str(path)


def row_identity(item):
    fields = ("order", "title", "shop_name", "kaogujia_key", "product_id", "detail_link")
    return {key: item.get(key) for key in fields}


def same_product(view, route, item):
    return view.get("path") == route and normalize_title(view.get("title")) == normalize_title(item["title"])


def save_product_assets(browser, detail, result, folder):
    image_url = detail.get("image")
    if image_url:
        image = browser.bytes_from_page(image_url) or read_public_image(image_url)
        extension = image_extension(image)
        if extension and len(image) <= MAX_IMAGE_BYTES:
            result["main_images"].append({
                "path": save_bytes(folder / ("主图" + extension), image),
                "source": "考古加商品详情头图", "width": detail["width"], "height": detail["height"],
                "source_url": source_ref(image_url),
            })
    result["main_image_status"] = "saved" if result["main_images"] else "image_not_saved"
    for url in detail.get("videos", []):
        if not url:
            continue
        video = browser.bytes_from_page(url, complete_response=True)
        extension = video_extension(video)
        if extension:
            result["videos"].append({"path": save_bytes(folder / ("商品视频" + extension), video),
                                     "source": "考古加商品详情原视频"})
            break
        if url == source_ref(url):
            result["videos"].append({"url": url, "source": "考古加商品详情视频链接"})
    if any(entry.get("path") for entry in result["videos"]):
        result["product_video_status"] = "saved"
    elif result["videos"]:
        result["product_video_status"] = "link_only"
    else:
        result["product_video_status"] = "player_without_complete_asset" if detail.get("videos") else "no_product_video_in_detail"


def try_playback(browser, entry, folder):
    url = entry["url"]
    video_id = url.rsplit("/", 1)[-1]
    expected_paths = (f"/video/{video_id}", f"/share/video/{video_id}")
    browser.navigate(url)
    deadline = time.monotonic() + 8
    seen_media = False
    while time.monotonic() < deadline:
        state = browser.evaluate(PLAYER_JS)
        if state.get("verification"):
            return "account_or_security_check"
        if state.get("unavailable"):
            return "post_unavailable"
        if state.get("host") == "www.douyin.com" and state.get("path") in expected_paths:
            for media in state.get("films", []):
                address = media["url"]
                seen_media = True
                data = browser.bytes_from_page(address, complete_response=True)
                extension = video_extension(data)
                if extension:
                    entry["path"] = save_bytes(folder / ("关联推广视频" + extension), data)
                    entry["collection_status"] = "saved"
                    return "saved_original_playback_response"
                if browser.media.get(address, (None, None))[1] == 206 and media["ready"] >= 2:
                    return "playback_range_response_no_full_file"
        time.sleep(0.3)
    return "playback_media_not_complete" if seen_media else "public_share_no_verified_player"


def collect_related(browser, item, result, folder):
    route = RELATED + item["kaogujia_key"]
    result["related_video_page"] = MARKET + route
    browser.navigate(result["related_video_page"])
    def ready(view):
        return view.get("path") == route and (
            (view.get("title") and view.get("items")) or view.get("restricted")
            or (view.get("empty") and not view.get("loading") and browser.related_status == 200)
        )
    view = poll(browser, RELATED_JS, ready, 13)
    if not same_product(view, route, item):
        result["related_video_status"] = "related_page_not_verified"
        return
    if not view.get("items"):
        if view.get("restricted"):
            result["related_video_status"] = "restricted"
        else:
            status = "empty_current_period" if ready(view) else "related_result_unconfirmed"
            result["related_video_status"] = status
        return
    for record in view["items"]:
        link = share_url(record.get("url"))
        entry = {"title": record.get("title"), "source": "考古加关联推广视频", "collection_status": "metadata_only"}
        if link:
            entry.update(url=link, collection_status="public_share_link")
        if record.get("deleted") in (True, 1, "1"):
            entry["deleted_status"] = "deleted"
        result["related_videos"].append(entry)
    public_links = [entry for entry in result["related_videos"] if entry.get("url")]
    result["related_video_status"] = "public_links" if public_links else "metadata_only"
    usable = [entry for entry in public_links if not entry.get("deleted_status")]
    for entry in usable[:2]:
        outcome = try_playback(browser, entry, folder)
        entry["playback_check"] = outcome
        if entry.get("path"):
            result["related_video_status"] = "saved_one_video"
        if outcome != "post_unavailable":
            break


def capture_assets(browser, item, root):
    result = {**row_identity(item), "main_images": [], "videos": [], "related_videos": []}
    key = item["kaogujia_key"]
    route = OVERVIEW + key
    folder = root / "考古加素材" / f"{item['order']:02d}-{key}"
    browser.navigate(MARKET + route)
    detail = poll(browser, DETAIL_JS, lambda view: (
        view.get("path", "").startswith("/loginPage")
        or (view.get("path") == route and view.get("title") and view.get("width", 0) > 30)
    ), 17)
    if detail.get("path", "").startswith("/loginPage"):
        result.update(detail_status="login_required", detail_note="考古加登录已失效")
    elif not same_product(detail, route, item):
        status = "detail_identity_unverified" if detail.get("title") else "detail_not_loaded"
        result.update(detail_status=status, detail_note="详情未载入或商品标题与源列表无法核对")
    else:
        result.update(detail_status="verified_detail", matched_by="kaogujia_key_and_title")
        save_product_assets(browser, detail, result, folder)
        if detail.get("related"):
            try:
                collect_related(browser, item, result, folder)
            except (RuntimeError, OSError, websocket.WebSocketException) as error:
                result.update(related_video_status="related_browser_failure", related_video_error=type(error).__name__)
        else:
            result["related_video_status"] = "no_related_tab"
    return result


def load_rows(source):
    payload = json.loads(source.read_text(encoding="utf-8-sig"))
    rows = payload.get("items") if isinstance(payload, dict) else payload
    if not isinstance(rows, list) or not rows:
        raise ValueError("商品页 JSON 没有商品行。")
    orders = set()
    for row in rows:
        if not isinstance(row, dict):
            raise ValueError("商品行结构变化，预期字典。")
        order, key = row.get("order"), row.get("kaogujia_key")
        if type(order) is not int or order < 1 or order in orders:
            raise ValueError("商品行号重复或无效。")
        if not isinstance(key, str) or not re.fullmatch(r"[A-Za-z0-9]{8,30}", key):
            raise ValueError(f"第 {order} 行考古加商品键无效。")
        if not isinstance(row.get("title"), str) or not normalize_title(row["title"]):
            raise ValueError(f"第 {order} 行没有有效商品标题。")
        if row.get("detail_link") != MARKET + OVERVIEW + key:
            raise ValueError(f"第 {order} 行详情链接与考古加商品键不一致。")
        orders.add(order)
    return sorted(rows, key=lambda row: row["order"])


def load_progress(output, rows):
    if not output.is_file():
        return {}
    current = {row["order"]: row_identity(row) for row in rows}
    document = json.loads(output.read_text(encoding="utf-8-sig"))
    previous = document.get("items") if isinstance(document, dict) else None
    if not isinstance(previous, list):
        raise ValueError("现有详情素材 JSON 缺少采集结果。")
    return {
        row["order"]: row for row in previous
        if row_identity(row) == current.get(row["order"])
        and row.get("detail_status") == "verified_detail"
        and row.get("related_video_status") in COMPLETED_RELATED_STATUSES
        and row.get("main_images")
        and all(Path(image["path"]).is_file() for image in row["main_images"])
    }


def coverage(rows):
    shares = [v["url"] for row in rows for v in row.get("related_videos", []) if v.get("url")]
    images = [v for row in rows for v in row.get("main_images", []) if v.get("path")]
    return {
        "detail_rows": sum(row.get("detail_status") == "verified_detail" for row in rows),
        "main_images_saved": len(images),
        "original_product_video_files": sum(bool(v.get("path")) for row in rows for v in row.get("videos", [])),
        "related_promotional_video_link_records": len(shares),
        "unique_related_promotional_video_links": len(set(shares)),
        "related_promotional_video_files": sum(bool(v.get("path")) for row in rows for v in row.get("related_videos", [])),
        "related_status": dict(Counter(row.get("related_video_status") or "unverified_detail" for row in rows)),
        "main_image_dimensions": dict(Counter(f"{v['width']}x{v['height']}" for v in images)),
    }


def collect(page, output, port, workers):
    rows = load_rows(page)
    recorded = load_progress(output, rows)
    remaining = [item for item in rows if item["order"] not in recorded]
    lock = threading.Lock()

    def checkpoint():
        items = [recorded[order] for order in sorted(recorded)]
        report = {"source": str(page), "source_page": PRODUCT_LIST, "expected_items": len(rows),
                  "coverage": coverage(items), "items": items,
                  "video_coverage_note": "关联视频来自各商品默认日期范围的第一页，尝试打开首条未删除的公开分享链接。",
                  "original_video_coverage_note": "商品本体视频来自考古加商品概览头卡中可核实的播放器或完整响应。"}
        save_bytes(output, (json.dumps(report, ensure_ascii=False, indent=2) + "\n").encode("utf-8"))

    if not remaining:
        checkpoint()
        return coverage(list(recorded.values()))
    with urlopen(f"http://127.0.0.1:{port}/json/version", timeout=4) as response:
        address = json.load(response)["webSocketDebuggerUrl"]

    def worker(items):
        browser = None
        try:
            for item in items:
                try:
                    if browser is None:
                        browser = AssetBrowser(address)
                    result = capture_assets(browser, item, output.parent)
                    if result.get("related_video_status") == "related_browser_failure":
                        browser.close()
                        browser = None
                except (RuntimeError, OSError, websocket.WebSocketException) as error:
                    result = {**row_identity(item), "detail_status": "collection_failure", "reason": type(error).__name__,
                              "main_images": [], "videos": [], "related_videos": []}
                    if browser:
                        browser.close()
                        browser = None
                result["captured_at"] = datetime.now().astimezone().isoformat(timespec="seconds")
                with lock:
                    recorded[item["order"]] = result
                    checkpoint()
                    print(f"已记录 {len(recorded)}/{len(rows)} · 第 {item['order']} 行 · {result['detail_status']}", flush=True)
        finally:
            if browser:
                browser.close()
    count = min(workers, len(remaining))
    with ThreadPoolExecutor(max_workers=count) as pool:
        futures = [pool.submit(worker, remaining[index::count]) for index in range(count)]
        for future in futures:
            future.result()
    return coverage(list(recorded.values()))


def main():
    parser = argparse.ArgumentParser(description="从已登录的 Chrome 采集考古加详情主图和关联视频分享链接")
    parser.add_argument("page", type=Path, help="kaogujia_page.py 生成的商品页 JSON")
    parser.add_argument("output", type=Path, help="详情素材记录 JSON，已有结果可续跑")
    parser.add_argument("--port", type=int, default=9223, help="考古加 Chrome 调试端口，默认 9223")
    parser.add_argument("--workers", type=int, default=2, help="并发打开的后台标签数，默认 2")
    args = parser.parse_args()
    if not 1 <= args.port <= 65535 or args.workers < 1:
        parser.error("端口或并发标签数不正确。")
    if args.page.resolve() == args.output.resolve():
        parser.error("详情输出路径须与商品页输入路径不同。")
    try:
        result = collect(args.page.resolve(), args.output.resolve(), args.port, args.workers)
    except (RuntimeError, ValueError, OSError, websocket.WebSocketException) as error:
        print(f"素材采集未完成 · {error}", file=sys.stderr)
        return 1
    print(json.dumps(result, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
