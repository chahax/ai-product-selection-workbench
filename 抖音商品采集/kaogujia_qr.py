from __future__ import annotations

import argparse
import base64
from datetime import datetime
from io import BytesIO
import json
from pathlib import Path
import re
import subprocess
from urllib.request import urlopen

from PIL import Image, ImageOps
import websocket
import zxingcpp

from douyin.android import qr_product_id
from kaogujia_assets import AssetBrowser, DETAIL_JS, OVERVIEW, load_rows, poll, same_product, save_bytes
from kaogujia_page import PRODUCT_LIST, PROFILE, start_chrome


READ_QR = r"""
(() => {
  const canvas = document.querySelector('.scan-content-code canvas');
  return canvas ? {png: canvas.toDataURL('image/png')} : {};
})()
"""


def decode_qr(image):
    codes = zxingcpp.read_barcodes(image, formats=zxingcpp.BarcodeFormat.QRCode)
    return codes[0].text if len(codes) == 1 else None


def read_verified_canvas(browser, expected_id):
    result = {}

    def decoded(state):
        data = state.get("png", "")
        prefix = "data:image/png;base64,"
        if not data.startswith(prefix) or len(data) > 200_000:
            return False
        raw = base64.b64decode(data[len(prefix):], validate=True)
        with Image.open(BytesIO(raw)) as canvas:
            padded = ImageOps.expand(canvas.convert("RGB"), border=12, fill="white")
        enlarged = padded.resize((padded.width * 6, padded.height * 6), Image.Resampling.NEAREST)
        url = decode_qr(enlarged)
        if not url:
            return False
        if qr_product_id(url) == expected_id:
            destination = BytesIO()
            enlarged.save(destination, format="PNG")
            result.update(raw=raw, enlarged=destination.getvalue())
        else:
            result["mismatch"] = True
        return True

    poll(browser, READ_QR, decoded, 7)
    return result


def capture_qr(browser, item, output_dir):
    result = {**item, "qr_status": "not_found", "qr_file": None, "qr_raw_file": None}
    expected_id = item.get("product_id")
    if not isinstance(expected_id, str) or not re.fullmatch(r"\d{10,25}", expected_id):
        return {**result, "qr_status": "missing_product_id"}

    route = OVERVIEW + item["kaogujia_key"]
    browser.navigate(item["detail_link"])
    detail = poll(browser, DETAIL_JS, lambda view: (
        view.get("path", "").startswith("/loginPage") or
        (view.get("path") == route and view.get("title"))
    ), 14)
    if detail.get("path", "").startswith("/loginPage"):
        return {**result, "qr_status": "login_required"}
    if not same_product(detail, route, item):
        return {**result, "qr_status": "detail_identity_unverified"}

    images = read_verified_canvas(browser, expected_id)
    if images.get("mismatch"):
        return {**result, "qr_status": "product_id_mismatch"}
    if not images:
        return result
    filename = f"{item['order']:02d}-{expected_id}.png"
    return {
        **result,
        "qr_status": "verified",
        "qr_file": save_bytes(output_dir / "二维码" / filename, images["enlarged"]),
        "qr_raw_file": save_bytes(output_dir / "二维码原图" / filename, images["raw"]),
        "qr_source": "考古加商品详情浮层 Canvas",
        "qr_captured_at": datetime.now().astimezone().isoformat(timespec="seconds"),
    }


def close_owned_browser(process, browser):
    if process is not None and process.poll() is None:
        try:
            if browser is not None:
                browser.call("Browser.close")
            process.wait(timeout=4)
        except (RuntimeError, OSError, subprocess.TimeoutExpired, websocket.WebSocketException):
            process.terminate()


def collect_qr(page, output, *, endpoint=None, start=1, end=None, resume=False):
    page, output = page.resolve(), output.resolve()
    if page == output:
        raise ValueError("二维码清单路径须与商品页输入路径不同。")
    rows = load_rows(page)
    selected = [row for row in rows if row["order"] >= start and (end is None or row["order"] <= end)]
    if not selected:
        raise ValueError("所选行号范围没有商品。")
    records = {}
    if resume and output.is_file():
        source_by_order = {row["order"]: row for row in rows}
        previous = json.loads(output.read_text(encoding="utf-8-sig"))["items"]
        for record in previous:
            row = source_by_order.get(record["order"])
            if row and all(record.get(key) == value for key, value in row.items()):
                records[record["order"]] = record

    def checkpoint():
        ordered = [records[order] for order in sorted(records)]
        data = {"source": PRODUCT_LIST, "source_list": str(page), "expected_items": len(rows), "items": ordered}
        save_bytes(output, (json.dumps(data, ensure_ascii=False, indent=2) + "\n").encode("utf-8"))

    pending = []
    for item in selected:
        earlier = records.get(item["order"], {})
        if (earlier.get("qr_status") == "verified" and all(earlier.get(key) and Path(earlier[key]).is_file()
               for key in ("qr_file", "qr_raw_file"))):
            continue
        pending.append(item)
    if not pending:
        checkpoint()
        return records

    process = browser = None
    try:
        if endpoint is None:
            process, endpoint = start_chrome(PROFILE)
        for item in pending:
            try:
                if browser is None:
                    browser = AssetBrowser(endpoint)
                result = capture_qr(browser, item, output.parent)
            except (RuntimeError, OSError, ValueError, websocket.WebSocketException) as error:
                result = {**item, "qr_status": "collection_failure", "error": type(error).__name__}
                if browser is not None:
                    browser.close()
                    browser = None
            records[item["order"]] = result
            checkpoint()
            print(f"二维码 第 {item['order']} 行 · {result['qr_status']}", flush=True)
            if result["qr_status"] == "login_required":
                raise RuntimeError("指定 Chrome 用户目录的考古加会话已失效。")
    finally:
        close_owned_browser(process, browser)
        if browser is not None:
            browser.close()
    return records


def main():
    parser = argparse.ArgumentParser(description="从考古加商品详情采集并逐项核对二维码")
    parser.add_argument("page", type=Path, help="考古加商品列表 JSON")
    parser.add_argument("output", type=Path, help="二维码验收 JSON")
    parser.add_argument("--port", type=int, help="同一 D:\\ChromeDebug\\User Data 浏览器实例的调试端口")
    parser.add_argument("--start", type=int, default=1, help="起始商品行号")
    parser.add_argument("--end", type=int, help="结束商品行号")
    parser.add_argument("--resume", action="store_true", help="保留已有的已核验二维码，重测其余商品")
    args = parser.parse_args()
    if args.start < 1 or (args.end is not None and args.end < args.start):
        parser.error("商品行号不正确。")
    if args.port is not None and not 1 <= args.port <= 65535:
        parser.error("端口不正确。")
    try:
        endpoint = None
        if args.port is not None:
            with urlopen(f"http://127.0.0.1:{args.port}/json/version", timeout=4) as response:
                endpoint = json.load(response)["webSocketDebuggerUrl"]
        collect_qr(args.page, args.output, endpoint=endpoint, start=args.start, end=args.end, resume=args.resume)
    except (OSError, RuntimeError, ValueError, websocket.WebSocketException) as error:
        parser.exit(1, f"二维码采集未完成 · {error}\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
