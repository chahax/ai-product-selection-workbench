from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import re
import subprocess
import time
from datetime import datetime
from urllib.parse import parse_qs, urlsplit

ID_PATTERN = re.compile(r"\d{10,25}")
SLOT_PATTERN = re.compile(r"/sdcard/Pictures/[A-Za-z0-9_-]+[.]png")
DEFAULT_APP_PACKAGE = "com.ss.android.ugc.aweme"
DEFAULT_PRODUCT_ACTIVITY = "ProductDetailV2Activity"
ANDROID_IDENTIFIER = r"[A-Za-z_][A-Za-z0-9_]*"
INTERVENTIONS = ("identity_verification", "app_login_required")


def write_json(path, value):
    scratch = path.with_name(path.name + ".writing")
    scratch.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    scratch.replace(path)


def url_id(url):
    if not isinstance(url, str):
        return None
    try:
        parsed = urlsplit(url)
    except ValueError:
        return None
    if (parsed.scheme, parsed.netloc, parsed.path) != (
        "https", "haohuo.jinritemai.com", "/ecommerce/trade/detail/index.html"
    ):
        return None
    values = parse_qs(parsed.query).get("id", [])
    return values[0] if len(values) == 1 and ID_PATTERN.fullmatch(values[0]) else None


def parse_rows(path):
    source = json.loads(path.read_text(encoding="utf-8-sig"))
    if isinstance(source, dict):
        source = source.get("items") or source.get("rows") or [source]
    if not isinstance(source, list) or not source:
        raise ValueError("输入需要包含按页面顺序排列的商品数组")
    rows = []
    for index, item in enumerate(source, 1):
        if not isinstance(item, dict):
            raise ValueError(f"第 {index} 行不是商品记录")
        provided_id = item.get("product_id") or item.get("id")
        link_id = url_id(item.get("url"))
        identifier = str(provided_id or link_id or "")
        if not ID_PATTERN.fullmatch(identifier):
            raise ValueError(f"第 {index} 行缺少有效商品 ID")
        if link_id and identifier != link_id:
            raise ValueError(f"第 {index} 行商品 ID 与链接不一致")
        row = {
            **item,
            "title": str(item.get("title") or "").strip(),
            "shop": str(item.get("shop") or item.get("shop_name") or "").strip(),
            "product_id": identifier,
        }
        qr_file = item.get("qr_file")
        if qr_file:
            if not isinstance(qr_file, str):
                raise ValueError(f"第 {index} 行二维码路径格式不正确")
            source_dir = path.resolve().parent
            qr_path = (source_dir / qr_file).resolve()
            if not qr_path.is_relative_to(source_dir) or qr_path.suffix.casefold() != ".png":
                raise ValueError(f"第 {index} 行二维码须为列表目录内的 PNG")
            row["_qr_path"] = qr_path
        rows.append(row)
    return rows


def frozen_source(rows, route):
    identity = [
        {key: row.get(key) for key in ("order", "product_id", "title", "shop", "url", "search_query", "qr_file")}
        for row in rows
    ]
    payload = json.dumps(identity, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return {"format_version": 1, "route": route,
            "input_sha256": hashlib.sha256(payload.encode("utf-8")).hexdigest(),
            "product_ids": [row["product_id"] for row in rows]}


def resume_rows(output, identity_path, identity, resume):
    if not resume:
        if output.exists() or identity_path.exists():
            raise ValueError("此输出已存在，请用 --resume 续跑同一份输入，或使用新的输出文件")
        return {}
    if not output.is_file() or not identity_path.is_file():
        raise ValueError("续跑需要原输出和配套的 source.json")
    previous_identity = json.loads(identity_path.read_text(encoding="utf-8-sig"))
    if previous_identity != identity:
        raise ValueError("输入列表、顺序或采集路线已变，请使用新的输出文件")
    rows = json.loads(output.read_text(encoding="utf-8-sig"))
    result = {}
    ids = identity["product_ids"]
    for row in rows:
        index = row["index"]
        if not isinstance(index, int) or not 1 <= index <= len(ids) or row["product_id"] != ids[index - 1]:
            raise ValueError("原结果中的商品 ID 与输入列表不一致")
        if index in result:
            raise ValueError("原结果行号重复")
        result[index] = row
    return result


def build_phone_class(android):
    class BatchPhone(android.Phone):
        deadline = None
        android_component = None

        def run(self, *args, timeout=8):
            if self.deadline is not None:
                remaining = self.deadline - time.monotonic()
                if remaining <= 0:
                    raise subprocess.TimeoutExpired("单件商品测试", 0)
                timeout = min(timeout, remaining)
            return super().run(*args, timeout=timeout)

        def top(self):
            activity = super().top()
            if activity:
                return activity
            raw = self.run("shell", "dumpsys", "window", "windows").decode("utf-8", "replace")
            return next((line.strip() for line in raw.splitlines() if "mCurrentFocus" in line), "")

        def launch_component(self):
            if self.android_component:
                return self.android_component
            raw = self.run(
                "shell", "cmd", "package", "resolve-activity", "--brief",
                "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER", self.app_package,
            ).decode("utf-8", "replace")
            matches = [line.strip() for line in raw.splitlines()
                       if re.fullmatch(re.escape(self.app_package) + r"/[\w.$]+", line.strip())]
            if not matches:
                raise RuntimeError("无法定位抖音的启动入口")
            self.android_component = matches[-1]
            return self.android_component

        def open_douyin(self):
            if not self.is_app_activity(self.top()):
                self.run("shell", "am", "start", "-n", self.launch_component(), timeout=8)
                android.wait_for(lambda: self.is_app_activity(self.top()), 8, "指定的抖音应用未回到前台")

        def restart_douyin(self):
            self.run("shell", "am", "force-stop", self.app_package, timeout=6)
            self.run("shell", "am", "start", "-n", self.launch_component(), timeout=8)

    return BatchPhone


def qr_product(phone, android, images, item, scratch, index, folder, page_only):
    data = android.product(phone, item, scratch, index, page_only)
    expected = android.normalize(item["title"])
    seen = android.normalize(data.get("title_seen") or "")
    if data["status"] != "verified_product":
        return data
    if data.get("matched_by") != "product_id":
        if not expected or expected != seen or (item["shop"] and not data.get("shop_seen")):
            result = {key: val for key, val in data.items()
                      if key not in ("skus", "page_prices", "matched_by", "sku_note")}
            return {**result, "status": "opened_unverified",
                    "reason": "扫码打开了商品页，未核对到商品 ID 或完整标题及店铺"}
        data["matched_by"] = "source_qr_and_exact_title_shop" if item["shop"] else "source_qr_and_exact_title"
    if not page_only and data.get("specs_shot"):
        try:
            captured, data["sku_image_note"] = images.capture_sku_images(phone, android, data.get("skus", []), folder)
            if captured:
                data["skus"] = captured
        except subprocess.TimeoutExpired:
            data["sku_image_note"] = "本件取图超时，已确认的报价仍保留"
        except (OSError, RuntimeError, ValueError, subprocess.SubprocessError) as exc:
            data["sku_image_note"] = f"规格图未完整核对（{type(exc).__name__}）"
    return data


def save_failure_preview(phone, path):
    try:
        preview = phone.screen()
        preview.thumbnail((540, 1200))
        preview.save(path)
        return str(path)
    except (OSError, RuntimeError, subprocess.SubprocessError):
        return None


def collect_item(phone, modules, item, row, folder, scratch, args):
    android, images, search = modules
    folder.mkdir(parents=True, exist_ok=True)
    phone.evidence_path = folder
    phone.deadline = time.monotonic() + args.seconds_per_item
    result = {**row, "directory": str(folder)}
    try:
        phone.open_douyin()
        if args.route == "qr":
            result.update(qr_product(phone, android, images, item, scratch, row["index"], folder, args.page_only))
        else:
            observed = search.recover(phone, android, images, item, folder, args.page_only, args.seconds_per_item)
            result.update(observed)
            if observed.get("status") != "verified_product":
                state = observed.get("search_status")
                result["status"] = state if state in (*INTERVENTIONS, "app_unresponsive") else "blocked"
                result["reason"] = observed.get("search_reason") or "抖音站内搜索未核对到匹配商品"
    except subprocess.TimeoutExpired:
        result.update(status="blocked", reason="本件商品测试超时")
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as exc:
        reason = str(exc) if isinstance(exc, (RuntimeError, ValueError)) else type(exc).__name__
        result.update(status="blocked", reason=reason)
    finally:
        phone.deadline = time.monotonic() + 4
        if not result.get("shot") and result.get("status") not in INTERVENTIONS:
            target = folder / "查看结果.png"
            if args.route == "native":
                try:
                    search.screenshot(phone, android, target)
                    result["shot"] = str(target)
                except search.AppIntervention as exc:
                    result.update(status=exc.status, search_status=exc.status, search_stage=exc.status, reason=str(exc))
                except (OSError, ValueError, RuntimeError, subprocess.SubprocessError):
                    pass
            else:
                shot = save_failure_preview(phone, target)
                if shot:
                    result["shot"] = shot
        phone.deadline = None
    return result


def restore_qr(phone, backup, scratch):
    for attempt in range(3):
        try:
            phone.write_verified(backup.read_bytes(), scratch)
            backup.unlink()
            return
        except (OSError, RuntimeError, subprocess.SubprocessError):
            if attempt < 2:
                time.sleep(1)
    print(f"扫码相册恢复未成功，已在 {backup} 保留备份。", flush=True)


def main(argv=None):
    parser = argparse.ArgumentParser(description="按考古加输入顺序采集抖音 App 的商品规格、券价和规格图")
    parser.add_argument("input", type=pathlib.Path, help="商品列表 JSON")
    parser.add_argument("output", type=pathlib.Path, nargs="?", help="输出的规格报价 JSON")
    parser.add_argument("--validate-only", action="store_true", help="只校验本地输入")
    parser.add_argument("--route", choices=("native", "qr"), default="native", help="站内搜索或抖音扫码")
    parser.add_argument("--serial", default="emulator-5554", help="ADB 中的模拟器序列号")
    parser.add_argument("--adb", type=pathlib.Path, help="adb 可执行文件路径")
    parser.add_argument("--app-package", default=DEFAULT_APP_PACKAGE, help="抖音应用包名")
    parser.add_argument("--product-activity", default=DEFAULT_PRODUCT_ACTIVITY, help="商品详情 Activity 名称")
    parser.add_argument("--mumu-manager", type=pathlib.Path, help="MuMuManager.exe 路径，启用 MuMu 中文输入")
    parser.add_argument("--mumu-index", type=int, default=0, help="MuMu 设备编号，默认为 0")
    parser.add_argument("--album-slot", help="扫码专用的已存在 PNG，位置须为 /sdcard/Pictures/<name>.png")
    parser.add_argument("--require-qr-files", action="store_true", help="扫码只使用各行指定的原始 PNG")
    parser.add_argument("--seconds-per-item", type=int, default=55, help="每件商品的主要操作时限")
    parser.add_argument("--start", type=int, help="从哪个输入行号开始测试")
    parser.add_argument("--end", type=int, help="在哪个输入行号结束测试")
    parser.add_argument("--resume", action="store_true", help="从同一列表的已有结果继续")
    parser.add_argument("--page-only", action="store_true", help="仅核对商品页")
    args = parser.parse_args(argv)
    try:
        rows = parse_rows(args.input)
        identity = frozen_source(rows, args.route)
        if args.app_package != DEFAULT_APP_PACKAGE:
            identity["app_package"] = args.app_package
        if args.validate_only:
            print(json.dumps({"rows": len(rows), "route": args.route,
                              "valid_qr_links": sum(url_id(row.get("url")) is not None for row in rows),
                              "qr_files_available": sum(row.get("_qr_path") is not None and row["_qr_path"].is_file() for row in rows)},
                             ensure_ascii=False))
            return 0
        if args.output is None:
            parser.error("请指定输出 JSON 路径")
        if not re.fullmatch(rf"{ANDROID_IDENTIFIER}(?:\.{ANDROID_IDENTIFIER})+", args.app_package):
            parser.error("--app-package 不是有效 Android 应用包名")
        if not re.fullmatch(rf"{ANDROID_IDENTIFIER}(?:[.$]{ANDROID_IDENTIFIER})*", args.product_activity):
            parser.error("--product-activity 不是有效 Activity 名称")
        if args.route == "qr" and args.app_package != DEFAULT_APP_PACKAGE:
            parser.error("扫码路线使用普通抖音的现有扫一扫界面")
        if args.mumu_manager is not None:
            args.mumu_manager = args.mumu_manager.resolve()
            if args.mumu_manager.name.casefold() != "mumumanager.exe" or not args.mumu_manager.is_file():
                parser.error("--mumu-manager 必须指向已安装的 MuMuManager.exe")
            if args.mumu_index < 0 or not re.fullmatch(r"(?:127\.0\.0\.1|localhost):[0-9]{1,5}", args.serial):
                parser.error("MuMu 路线需要非负设备编号及明确的本机 ADB 端口")
        if args.route == "qr" and not SLOT_PATTERN.fullmatch(args.album_slot or ""):
            parser.error("扫码路线需要指定 --album-slot /sdcard/Pictures/<name>.png")
        end = len(rows) if args.end is None else args.end
        if not 1 <= (args.start or 1) <= end <= len(rows) or args.seconds_per_item < 10:
            parser.error("测试范围或每件商品时限不正确")
        output = args.output.resolve()
        if output == args.input.resolve():
            parser.error("输出文件必须与输入文件不同")
        output.parent.mkdir(parents=True, exist_ok=True)
        frozen_path = output.with_name(output.stem + "-source.json")
        result = resume_rows(output, frozen_path, identity, args.resume)
    except (OSError, ValueError, KeyError, TypeError) as exc:
        parser.error(str(exc))

    from . import android, images, search

    evidence = output.parent / (output.stem + "-evidence")
    evidence.mkdir(exist_ok=True)
    indices = [index for index in range(args.start or 1, end + 1)
               if args.start is not None or index not in result]
    if not indices:
        print(json.dumps({"rows_already_recorded": len(result)}, ensure_ascii=False))
        return 0
    write_json(frozen_path, identity)
    write_json(output, [result[index] for index in sorted(result)])
    scratch = evidence / "temporary-qr.png"
    backup = evidence / "扫码相册恢复备份.png"
    if args.route == "qr" and backup.exists():
        parser.error("发现上次留下的扫码相册备份，请先恢复专用扫码图片")

    phone = None
    setup_error = None
    try:
        Phone = build_phone_class(android)
        phone = Phone(args.serial, evidence, args.album_slot, args.adb,
                      app_package=args.app_package, product_activity=args.product_activity)
        phone.mumu_manager = args.mumu_manager
        phone.mumu_index = args.mumu_index
        if phone.run("get-state").strip() != b"device":
            raise RuntimeError("模拟器未连接")
        if phone.screen().size != (1080, 2400):
            raise RuntimeError("当前规格面板模板需要 1080 × 2400 模拟器画面")
        if args.route == "qr":
            original = phone.run("exec-out", "cat", args.album_slot)
            if not original.startswith(b"\x89PNG\r\n\x1a\n"):
                raise RuntimeError("无法读取用作扫码测试的 PNG 图片")
            backup.write_bytes(original)
    except (OSError, RuntimeError, subprocess.SubprocessError) as exc:
        setup_error = str(exc) if isinstance(exc, RuntimeError) else type(exc).__name__

    try:
        for index in indices:
            item = rows[index - 1]
            row = {"index": index, "order": item.get("order", index), "product_id": item["product_id"],
                   "title_expected": item["title"], "shop": item["shop"], "route": args.route,
                   "app_package": args.app_package}
            if setup_error:
                row.update(status="not_attempted", reason=f"模拟器准备失败（{setup_error}）")
            elif args.route == "qr" and not url_id(item.get("url")):
                row.update(status="not_attempted", reason="缺少可核对的抖音商品链接")
            elif args.route == "qr" and args.require_qr_files and not item.get("_qr_path"):
                row.update(status="not_attempted", reason="缺少该商品考古加原始二维码路径")
            elif args.route == "native" and not item["title"]:
                row.update(status="not_attempted", reason="缺少供站内搜索核对的商品标题")
            else:
                folder = evidence / f"{index:02d}-{item['product_id']}"
                row = collect_item(phone, (android, images, search), item, row, folder, scratch, args)
            row["tested_at"] = datetime.now().astimezone().isoformat(timespec="seconds")
            result[index] = row
            write_json(output, [result[idx] for idx in sorted(result)])
            images_count = sum(bool(sku.get("image")) for sku in row.get("skus") or [])
            print(json.dumps({"index": index, "total": len(rows), "status": row["status"],
                              "search_submitted": row.get("search_submitted", False),
                              "skus": len(row.get("skus") or []), "images": images_count}), flush=True)
            if row["status"] in INTERVENTIONS:
                break
    finally:
        if phone is not None:
            phone.deadline = None
            if backup.is_file() and setup_error is None:
                restore_qr(phone, backup, scratch)
            try:
                phone.run("shell", "rm", "-f", phone.remote_qr, phone.remote_ui)
                scratch.unlink(missing_ok=True)
            except (OSError, subprocess.SubprocessError):
                pass
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

