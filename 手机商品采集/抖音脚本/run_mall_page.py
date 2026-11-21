from __future__ import annotations

import argparse
from contextlib import contextmanager
from datetime import datetime
import json
from pathlib import Path
import subprocess
import sys
import time
from urllib.parse import urlsplit
from urllib.request import urlopen

import websocket

import kaogujia_page
import merge_results


SCRIPT_DIR = Path(__file__).resolve().parent
RESULTS_DIR = SCRIPT_DIR.parent / "采集结果" / "抖音"
PROFILE = kaogujia_page.PROFILE
DEFAULT_QUERY = {"page": 1, "limit": 50, "sort_field": "sales", "sort": 0}
DEFAULT_FILTERS = {"keyword": "吹风机", "period": 1}


def assert_profile(connection):
    target = connection.call("Target.createTarget", {"url": "about:blank", "background": True})["targetId"]
    try:
        session = connection.call("Target.attachToTarget", {"targetId": target, "flatten": True})["sessionId"]
        connection.call("Page.navigate", {"url": "chrome://version/"}, session)
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            result = connection.call("Runtime.evaluate", {
                "expression": 'document.getElementById("profile_path")?.textContent.trim()',
                "returnByValue": True,
            }, session)
            if profile := result.get("result", {}).get("value"):
                if Path(profile).resolve().parent == PROFILE.resolve():
                    return
                raise RuntimeError(f"该端口未使用指定的 Chrome 用户目录 {PROFILE}")
            time.sleep(0.2)
        raise RuntimeError("无法核对该 Chrome 调试端口的用户目录")
    finally:
        connection.call("Target.closeTarget", {"targetId": target})


@contextmanager
def chrome_port(port):
    process = None
    connection = None
    try:
        if port is None:
            if not (PROFILE / "Local State").is_file():
                raise RuntimeError(f"找不到已配置的 Chrome 用户目录 {PROFILE}")
            process, endpoint = kaogujia_page.start_chrome(PROFILE)
            port = urlsplit(endpoint).port
        else:
            with urlopen(f"http://127.0.0.1:{port}/json/version", timeout=4) as response:
                endpoint = json.load(response)["webSocketDebuggerUrl"]
            address = urlsplit(endpoint)
            if address.scheme != "ws" or address.hostname != "127.0.0.1" or address.port != port:
                raise RuntimeError("Chrome 未返回对应的本机调试地址")
        connection = kaogujia_page.ChromeConnection(endpoint, {}, {})
        assert_profile(connection)
        yield port
    finally:
        if connection is not None:
            if process is not None and process.poll() is None:
                try:
                    connection.call("Browser.close")
                except (RuntimeError, OSError, websocket.WebSocketException):
                    pass
            connection.close()
        if process is not None and process.poll() is None:
            try:
                process.wait(timeout=4)
            except subprocess.TimeoutExpired:
                process.terminate()


def run_step(title, *arguments):
    print(title, flush=True)
    command = [sys.executable, "-X", "utf8", *map(str, arguments)]
    return subprocess.run(command, cwd=SCRIPT_DIR, check=False).returncode


def setting_args(settings):
    for key, value in settings.items():
        if value is not None:
            yield "--" + key.replace("_", "-")
            yield str(value)


def check_page_settings(page, query, filters):
    saved, _ = merge_results.unpack(page)
    for name, selected in (("query", query), ("filters", filters)):
        original = saved.get(name) or {}
        if any(value is not None and original.get(key) != value for key, value in selected.items()):
            raise ValueError("续跑参数与原商品页不同，请保留原筛选条件或使用新的结果目录")


def atomic_text(path, content):
    temporary = path.with_name(path.name + ".writing")
    temporary.write_text(content, encoding="utf-8")
    temporary.replace(path)


def write_report(page, assets, quotes):
    assets = assets if assets.is_file() else None
    quotes = quotes if quotes.is_file() else None
    report = merge_results.merge(page, assets, quotes)
    _, raw_rows = merge_results.unpack(quotes)
    visited = [row for row in raw_rows if row.get("status") not in (None, "not_attempted", "not_in_report")]
    cleanups = [row["phone_qr_cleanup"] for row in raw_rows if isinstance(row.get("phone_qr_cleanup"), dict)]
    verified = report["coverage"]["identity_verified"]
    confirmed_removals = sum(result.get("file_removed") is True and result.get("media_entry_removed") is True
                             for result in cleanups)
    metrics = {
        "attempted": len(visited),
        "verified": verified,
        "unverified": len(visited) - verified,
        "unattempted": len(report["items"]) - len(visited),
        "qr_cleanup_records": len(cleanups),
        "qr_cleanup_verified": confirmed_removals,
        "goods_with_tags": sum(bool(row["douyin"]["product_tags"]) for row in report["items"]),
    }
    report["phone_run"] = metrics
    header, body = merge_results.render(report).split("\n\n", 1)
    overview = ["## 本轮完成情况", "",
                f"手机端尝试 {metrics['attempted']}/{len(report['items'])} 件，核对商品 {verified} 件，"
                f"未核对 {metrics['unverified']} 件，未尝试 {metrics['unattempted']} 件。",
                f"有 {metrics['goods_with_tags']} 件采到商品标签。",
                f"手机二维码清理记录 {len(cleanups)} 条，其中 {confirmed_removals} 条确认图片与相册索引均已删除。"]
    atomic_text(page.parent / "采集实测报告.md", header + "\n\n" + "\n".join(overview) + "\n\n" + body)
    atomic_text(page.parent / "合并采集结果.json", json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(f"汇总报告已保存 · {page.parent / '采集实测报告.md'}", flush=True)


def main():
    parser = argparse.ArgumentParser(
        description="用指定 Chrome 账号和 USB 真机采集考古加一整页",
        epilog="新一轮默认沿用吹风机、周期 1、第 1 页、每页 50 件、按销量降序。续跑沿用原结果目录的筛选条件。",
    )
    parser.add_argument("output_dir", nargs="?", type=Path, help="结果目录，省略时创建带时间的目录")
    parser.add_argument("--serial", required=True, help="USB 真机的 ADB 序列号")
    parser.add_argument("--adb", type=Path, help="adb.exe 路径")
    parser.add_argument("--port", type=int, help="已打开的指定 Chrome 用户目录的本机调试端口")
    parser.add_argument("--limit", type=int, help="本页最多读取多少件，新一轮默认为 50")
    parser.add_argument("--page", type=int, help="商品库页码")
    parser.add_argument("--keyword")
    parser.add_argument("--period", type=int)
    parser.add_argument("--sort-field")
    parser.add_argument("--sort", type=int, choices=(0, 1))
    parser.add_argument("--start", type=int, help="只在手机端从指定条目开始")
    parser.add_argument("--end", type=int, help="只在手机端跑至指定条目")
    parser.add_argument("--resume", action="store_true", help="用原目录中的商品列表续跑")
    args = parser.parse_args()
    if args.port is not None and not 1 <= args.port <= 65535:
        parser.error("Chrome 端口无效")
    if any(value is not None and value < 1 for value in (args.limit, args.page, args.start, args.end)):
        parser.error("条数、页码和行号须大于零")
    if args.start is not None and args.end is not None and args.start > args.end:
        parser.error("起始行不能超过结束行")
    if args.resume and args.output_dir is None:
        parser.error("续跑需要指定原结果目录")

    timestamp = datetime.now().astimezone().strftime("%Y%m%d-%H%M%S")
    output = (args.output_dir or RESULTS_DIR / f"考古加真机商城-{timestamp}").resolve()
    page, assets, qrs, quotes = (output / name for name in (
        "商品列表.json", "考古加详情素材.json", "二维码验收.json", "抖音规格报价.json"))
    if not args.resume and any(path.exists() for path in (page, assets, qrs, quotes)):
        parser.error("此目录已有采集结果，请用 --resume 或指定新的目录")
    if args.resume and not page.is_file() and any(path.exists() for path in (assets, qrs, quotes)):
        parser.error("结果目录缺少原商品列表，无法核对续跑输入")

    query = {key: getattr(args, key) for key in ("limit", "page", "sort_field", "sort")}
    filters = {key: getattr(args, key) for key in ("keyword", "period")}
    ready_to_report = False
    exit_code = 0
    try:
        if page.is_file():
            check_page_settings(page, query, filters)
        with chrome_port(args.port) as port:
            if not page.is_file():
                page_query = {**DEFAULT_QUERY, **{key: value for key, value in query.items() if value is not None}}
                page_filters = {**DEFAULT_FILTERS, **{key: value for key, value in filters.items() if value is not None}}
                exit_code = run_step("读取考古加商品库", "kaogujia_page.py", page, "--port", port,
                                     *setting_args(page_query), *setting_args(page_filters))
                if exit_code:
                    return exit_code
            ready_to_report = True
            asset_exit = run_step("保存考古加详情主图和视频信息", "kaogujia_assets.py", page, assets, "--port", port)
            qr_args = ["--resume"] if qrs.is_file() else []
            qr_exit = run_step("保存并核对考古加二维码", "kaogujia_qr.py", page, qrs, "--port", port, *qr_args)
            exit_code = asset_exit or qr_exit
        if qr_exit == 0:
            phone_options = dict(adb=args.adb.resolve() if args.adb else None, start=args.start, end=args.end)
            resume_flag = ["--resume"] if quotes.is_file() else []
            phone_exit = run_step("使用 USB 真机的抖音商城扫码核价", "-m", "douyin.mall_collect", qrs, quotes,
                                  "--serial", args.serial, *setting_args(phone_options), *resume_flag)
            exit_code = exit_code or phone_exit
    except KeyboardInterrupt:
        exit_code = 130
    except (RuntimeError, ValueError, OSError, KeyError, websocket.WebSocketException) as error:
        print(f"本轮采集停止 · {error}", file=sys.stderr)
        exit_code = 1
    finally:
        if ready_to_report:
            try:
                write_report(page, assets, quotes)
            except (ValueError, OSError, KeyError) as error:
                print(f"汇总未完成 · {error}", file=sys.stderr)
                exit_code = exit_code or 1
    return exit_code


if __name__ == "__main__":
    sys.exit(main())
