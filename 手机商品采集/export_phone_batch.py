#!/usr/bin/env python3
r"""Read-only export of the collector's current phone batch (Python 3.9+).

Examples:
  python export_phone_batch.py --output D:\exports\collector-batch
  python export_phone_batch.py --serial PHONE_SERIAL --images --output D:\exports\collector-images

Phone commands are limited to exec-out/run-as local.ai.collector with readlink/cat.
The script never starts an app, changes a task, pushes a file, or deletes phone data.
"""

import argparse
import csv
import json
import os
import re
import subprocess
import sys
from collections import Counter
from datetime import datetime, timezone
from decimal import Decimal
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple


PACKAGE = "local.ai.collector"
APP_FILE_PREFIXES = (
    "/data/user/0/local.ai.collector/files/",
    "/data/data/local.ai.collector/files/",
)
RECORD_ID = re.compile(r"[A-Za-z0-9._-]{1,120}\Z")
SAFE_COMPONENT = re.compile(r"[A-Za-z0-9._-]+\Z")
PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"
TERMINAL = {"complete", "partial", "failed"}


class ExportError(Exception):
    pass


def text(value: Any) -> str:
    return value if isinstance(value, str) else ""


def object_value(value: Any) -> Dict[str, Any]:
    return value if isinstance(value, dict) else {}


def list_value(value: Any) -> List[Any]:
    return value if isinstance(value, list) else []


def default_adb() -> str:
    local = os.environ.get("LOCALAPPDATA")
    return str(Path(local) / "Android" / "Sdk" / "platform-tools" / "adb.exe") if local else "adb"


def valid_relative(path: str) -> bool:
    parts = path.split("/")
    return bool(parts) and all(part not in {"", ".", ".."} and SAFE_COMPONENT.fullmatch(part) for part in parts)


def own_relative(absolute: str) -> str:
    for prefix in APP_FILE_PREFIXES:
        if absolute.startswith(prefix):
            suffix = absolute[len(prefix):]
            if valid_relative(suffix):
                return "files/" + suffix
            break
    raise ExportError("路径不属于本应用允许读取的文件目录")


class AdbReader:
    def __init__(self, adb: str, serial: Optional[str]) -> None:
        self.command = [adb]
        if serial:
            self.command.extend(["-s", serial])

    def _run(self, arguments: List[str]) -> bytes:
        command = self.command + ["exec-out", "run-as", PACKAGE] + arguments
        try:
            result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                    timeout=30, check=False, shell=False)
        except FileNotFoundError as error:
            raise ExportError("找不到 adb，请用 --adb 指定 platform-tools 中的 adb.exe") from error
        except subprocess.TimeoutExpired as error:
            raise ExportError("读取手机文件超时，未修改手机数据") from error
        except OSError as error:
            raise ExportError("无法启动 adb：" + str(error)) from error
        if result.returncode:
            detail = result.stderr.decode("utf-8", errors="replace").strip()
            if not detail:
                detail = result.stdout.decode("utf-8", errors="replace").strip()
            raise ExportError("手机读取失败：" + (detail[:400] or "请检查 USB 调试、设备选择和应用是否允许 run-as"))
        return result.stdout

    def read_owned(self, relative: str) -> bytes:
        if not relative.startswith("files/") or not valid_relative(relative):
            raise ExportError("拒绝读取不合规的本应用文件路径")
        # Resolve on the phone first: an apparently owned symlink must not lead
        # into another app or the user's photo album.
        resolved = self._run(["readlink", "-f", relative]).decode("utf-8", errors="strict").strip()
        if own_relative(resolved) != relative:
            raise ExportError("手机文件的真实路径与本应用引用不一致，已拒绝读取")
        return self._run(["cat", resolved])

    def read_json(self, relative: str) -> Dict[str, Any]:
        try:
            value = json.loads(self.read_owned(relative).decode("utf-8-sig"))
        except (UnicodeError, json.JSONDecodeError) as error:
            raise ExportError("手机记录不是有效的 UTF-8 JSON：" + relative) from error
        if not isinstance(value, dict):
            raise ExportError("手机记录不是 JSON 对象：" + relative)
        return value


def quote_complete(sku: Dict[str, Any]) -> bool:
    return all(isinstance(sku.get(key), int) and not isinstance(sku.get(key), bool) and sku[key] >= 0
               for key in ("price_before_coupon_fen", "price_after_coupon_fen"))


def money(sku: Dict[str, Any], key: str) -> str:
    value = sku.get(key)
    if not isinstance(value, int) or isinstance(value, bool) or value < 0:
        return ""
    return format(Decimal(value) / Decimal(100), ".2f")


def effective_status(item: Dict[str, Any], record: Optional[Dict[str, Any]]) -> str:
    queued = text(item.get("status"))
    saved = text(record.get("status")) if record else ""
    if queued == "failed" or saved == "failed":
        return "failed"
    if not record or queued not in TERMINAL or saved not in TERMINAL:
        return "pending"
    if queued == "partial" or saved == "partial" or not list_value(record.get("skus")):
        return "partial"
    return "complete"


def collect_records(reader: AdbReader, batch: Dict[str, Any]) -> Tuple[List[Dict[str, Any]], List[Dict[str, Any]]]:
    batch_id = text(batch.get("batch_id"))
    if not batch_id or not isinstance(batch.get("items"), list):
        raise ExportError("当前批次缺少 batch_id 或有效的商品列表")
    rows = []
    records = []
    seen = set()
    for index, original in enumerate(batch["items"], 1):
        item = object_value(original)
        row = {"index": index, "item": item, "record": None, "issues": []}
        record_id = text(item.get("record_id"))
        if not item:
            row["issues"].append("商品列表项格式无效")
        elif not record_id:
            if text(item.get("status")) in TERMINAL:
                row["issues"].append("队列已标记终态，但没有 record_id")
        elif not RECORD_ID.fullmatch(record_id):
            row["issues"].append("record_id 格式不合规，未读取该文件")
        elif record_id in seen:
            row["issues"].append("多个商品重复引用同一 record_id，本项不重复计入已保存记录")
        else:
            try:
                record = reader.read_json("files/records/" + record_id + ".json")
                if record.get("batch_id") != batch_id:
                    raise ExportError("记录不属于导出开始时的当前批次，已排除")
                if record.get("record_id") != record_id:
                    raise ExportError("记录内部 record_id 与队列引用不一致，已排除")
                for identity in ("source_item_key", "product_id"):
                    expected, observed = text(item.get(identity)), text(record.get(identity))
                    if expected and observed and expected != observed:
                        raise ExportError(identity + " 与队列商品不一致，已排除")
                seen.add(record_id)
                row["record"] = record
                records.append(record)
                if text(item.get("status")) != text(record.get("status")):
                    row["issues"].append("队列与已保存记录的状态不一致")
                if record.get("status") == "complete" and not list_value(record.get("skus")):
                    row["issues"].append("记录标记 complete 但没有规格，本导出按 partial 统计")
            except ExportError as error:
                row["issues"].append(str(error))
        row["result_status"] = effective_status(item, row["record"])
        rows.append(row)
    return rows, records


def image_references(record: Dict[str, Any]) -> List[Dict[str, Any]]:
    references = []
    main = object_value(record.get("main_image"))
    if text(main.get("file")):
        references.append({"kind": "main_image", "spec_index": -1, "image_index": 0,
                           "original_path": main["file"]})
    for index, value in enumerate(list_value(record.get("product_images"))):
        image = object_value(value)
        file = text(image.get("file"))
        if file and file != text(main.get("file")):
            position = image.get("position")
            image_index = position - 1 if isinstance(position, int) and position > 0 else index
            references.append({"kind": "product_image", "spec_index": -1, "image_index": image_index,
                               "original_path": file})
    for index, original in enumerate(list_value(record.get("skus"))):
        sku = object_value(original)
        images = [object_value(sku.get("image"))] + [object_value(value) for value in list_value(sku.get("images"))]
        for number, image in enumerate(images):
            if text(image.get("file")):
                references.append({"kind": "sku_image", "spec_index": index, "image_index": max(0, number - 1),
                                   "original_path": image["file"]})
    return references


def destination(output: Path, relative: str) -> Path:
    target = (output / relative).resolve()
    try:
        target.relative_to(output)
    except ValueError as error:
        raise ExportError("输出路径超出 --output 目录，已拒绝写入") from error
    return target


def export_images(reader: AdbReader, records: List[Dict[str, Any]], output: Path) -> Tuple[List[Dict[str, Any]], Dict[str, str]]:
    manifest = []
    exported = {}
    cache = {}
    for record in records:
        for reference in image_references(record):
            path = reference["original_path"]
            entry = dict(reference, record_id=record["record_id"], exported_path="")
            try:
                relative = own_relative(path)
                if not relative.startswith("files/images/") or not relative.lower().endswith(".png"):
                    entry.update(status="skipped", reason="仅导出本应用 files/images 目录下记录引用的 PNG")
                else:
                    if relative not in cache:
                        try:
                            data = reader.read_owned(relative)
                            if not data.startswith(PNG_SIGNATURE):
                                raise ExportError("文件内容不是 PNG，未保存为图片")
                            local_relative = relative[len("files/"):]
                            local_file = destination(output, local_relative)
                            local_file.parent.mkdir(parents=True, exist_ok=True)
                            with local_file.open("xb") as stream:
                                stream.write(data)
                            cache[relative] = {"status": "exported", "exported_path": local_relative}
                        except (ExportError, OSError, UnicodeError) as error:
                            cache[relative] = {"status": "error", "reason": str(error), "exported_path": ""}
                    entry.update(cache[relative])
                    if entry["status"] == "exported":
                        exported[path] = entry["exported_path"]
            except ExportError as error:
                entry.update(status="rejected", reason=str(error))
            manifest.append(entry)
    return manifest, exported


def joined(values: List[Any]) -> str:
    return " | ".join(str(value) for value in values if value is not None)


def flags(value: Any) -> str:
    return "是" if value is True else "否" if value is False else "未提供"


def core_text(value: Any) -> str:
    if isinstance(value, str):
        return value if value.strip() else "未采到"
    if isinstance(value, (int, float)) and not isinstance(value, bool):
        return str(value)
    return "未采到"


def separate_names(record: Dict[str, Any]) -> Dict[str, str]:
    def first(*values: Any) -> str:
        for value in values:
            if isinstance(value, str) and value.strip():
                return value
        return "未采到"

    detail = object_value(record.get("source_detail"))
    return {
        "考古加商品名称": first(record.get("kaogujia_product_name"), record.get("source_title")),
        "抖音商品名称": first(record.get("douyin_product_name"), record.get("title")),
        "考古加店铺名称": first(record.get("kaogujia_shop_name"), detail.get("shop_name"), record.get("shop_name")),
        "抖音店铺名称": first(record.get("douyin_shop_name")),
    }


def product_and_sku_rows(rows: List[Dict[str, Any]], batch_id: str,
                         exported: Dict[str, str]) -> Tuple[List[Dict[str, Any]], List[Dict[str, Any]]]:
    products, skus = [], []
    for entry in rows:
        item, record = entry["item"], object_value(entry["record"])
        sku_values = [object_value(value) for value in list_value(record.get("skus"))]
        title = text(record.get("title")) or text(item.get("title"))
        source_title = text(record.get("source_title")) or text(item.get("source_title")) or text(item.get("title"))
        shop = text(record.get("shop_name")) or text(item.get("shop_name"))
        record_id = text(item.get("record_id"))
        main_path = text(object_value(record.get("main_image")).get("file"))
        source_detail = object_value(record.get("source_detail"))
        core = object_value(source_detail.get("core_data"))
        names = separate_names(record)
        products.append({
            "商品序号": entry["index"], "批次ID": batch_id, "记录ID": record_id,
            **names,
            "来源标识": text(item.get("source_item_key")), "商品标题": title, "来源标题": source_title,
            "店铺": shop, "导出结果": entry["result_status"], "队列状态": text(item.get("status")),
            "记录状态": text(record.get("status")), "实际规格数": len(sku_values),
            "页面声明款数": record.get("sku_count", ""), "完整报价规格数": sum(quote_complete(sku) for sku in sku_values),
            "商品销量原文": core_text(core.get("商品销量")), "直播销量原文": core_text(core.get("直播销量")),
            "视频销量原文": core_text(core.get("视频销量")), "商品卡销量原文": core_text(core.get("商品卡销量")),
            "核心数据周期": core_text(source_detail.get("core_data_period")),
            "核心数据日期范围": core_text(source_detail.get("core_data_date_text")),
            "核心数据完整": flags(source_detail.get("core_data_complete")),
            "主图原路径": main_path, "主图导出路径": exported.get(main_path, ""),
            "视频链接": text(record.get("video_url")),
            "失败或说明": text(record.get("collection_error")) or text(item.get("error")) or text(record.get("coverage_note")),
            "核对问题": joined(entry["issues"]),
        })
        for index, sku in enumerate(sku_values, 1):
            specifications = object_value(sku.get("specifications"))
            specification_text = joined([str(key) + "=" + str(value) for key, value in specifications.items()])
            references = [reference for reference in image_references({"skus": [sku]}) if reference["kind"] == "sku_image"]
            paths = list(dict.fromkeys(reference["original_path"] for reference in references))
            image = object_value(sku.get("image"))
            skus.append({
                "商品序号": entry["index"], "批次ID": batch_id, "记录ID": record_id,
                **names,
                "来源标识": text(item.get("source_item_key")), "商品标题": title, "店铺": shop,
                "商品结果": entry["result_status"], "规格序号": index,
                "规格名称": text(sku.get("name")) or specification_text, "规格组合": specification_text,
                "券前价标签": text(sku.get("price_before_label")), "券前价": money(sku, "price_before_coupon_fen"),
                "券后价标签": text(sku.get("price_after_label")), "券后价": money(sku, "price_after_coupon_fen"),
                "热销": flags(sku.get("hot")), "可售": flags(sku.get("available")),
                "报价完整": "是" if quote_complete(sku) else "否", "原采集完整标记": flags(sku.get("data_complete")),
                "图片原路径": joined(paths), "图片导出路径": joined([exported[path] for path in paths if path in exported]),
                "图片来源": text(image.get("source")), "图片宽": image.get("width", ""), "图片高": image.get("height", ""),
                "缺失字段": joined(list_value(sku.get("missing_fields"))),
                "备注": joined([sku.get(key) for key in ("note", "price_note", "image_note", "availability_note")]),
            })
    return products, skus


PRODUCT_FIELDS = ["商品序号", "批次ID", "记录ID", "考古加商品名称", "抖音商品名称", "考古加店铺名称", "抖音店铺名称",
                  "来源标识", "商品标题", "来源标题", "店铺", "导出结果", "队列状态", "记录状态",
                  "实际规格数", "页面声明款数", "完整报价规格数", "商品销量原文", "直播销量原文", "视频销量原文", "商品卡销量原文",
                  "核心数据周期", "核心数据日期范围", "核心数据完整", "主图原路径", "主图导出路径", "视频链接", "失败或说明", "核对问题"]
SKU_FIELDS = ["商品序号", "批次ID", "记录ID", "考古加商品名称", "抖音商品名称", "考古加店铺名称", "抖音店铺名称",
              "来源标识", "商品标题", "店铺", "商品结果", "规格序号", "规格名称", "规格组合",
              "券前价标签", "券前价", "券后价标签", "券后价", "热销", "可售", "报价完整", "原采集完整标记", "图片原路径", "图片导出路径",
              "图片来源", "图片宽", "图片高", "缺失字段", "备注"]


def write_json(output: Path, filename: str, value: Any) -> None:
    with destination(output, filename).open("x", encoding="utf-8", newline="\n") as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2)
        stream.write("\n")


def csv_cell(value: Any) -> Any:
    if isinstance(value, str) and value.lstrip(" \t\r\n").startswith(("=", "+", "-", "@")):
        return "'" + value
    return value


def write_csv(output: Path, filename: str, fields: List[str], rows: List[Dict[str, Any]]) -> None:
    with destination(output, filename).open("x", encoding="utf-8-sig", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=fields)
        writer.writeheader()
        for row in rows:
            writer.writerow({key: csv_cell(row.get(key, "")) for key in fields})


def main(argv: Optional[List[str]] = None) -> int:
    parser = argparse.ArgumentParser(description="只读导出手机采集应用的当前批次，不启动任务、不修改手机数据。")
    parser.add_argument("--output", required=True, type=Path, help="保存 JSON、BOM CSV 和可选图片的目录；不会覆盖已有导出文件")
    parser.add_argument("--adb", default=default_adb(), help="adb.exe 路径，默认 LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe")
    parser.add_argument("--serial", help="ADB 设备序列号，多台设备连接时指定")
    parser.add_argument("--images", action="store_true", help="导出本应用 files/images 下记录引用的 PNG，不读取用户相册")
    arguments = parser.parse_args(argv)
    reader = AdbReader(arguments.adb, arguments.serial)
    try:
        batch = reader.read_json("files/kaogu_batch.json")
        rows, records = collect_records(reader, batch)
        output = arguments.output.resolve()
        output.mkdir(parents=True, exist_ok=True)
        filenames = ["batch.json", "records.json", "summary.json", "products.csv", "skus.csv"]
        if arguments.images:
            filenames.append("image_manifest.json")
        if any(destination(output, filename).exists() for filename in filenames):
            raise ExportError("输出目录已有同名导出文件，请使用新的 --output 目录，已有文件不会覆盖")
        manifest, exported = export_images(reader, records, output) if arguments.images else ([], {})
        products, skus = product_and_sku_rows(rows, batch["batch_id"], exported)
        end_warning = ""
        try:
            end = reader.read_json("files/kaogu_batch.json")
            if end.get("batch_id") != batch["batch_id"]:
                end_warning = "导出期间手机切换了批次，本文件仅包含导出开始时的批次快照"
            elif end != batch:
                end_warning = "导出期间批次状态发生变化，本文件按开始时的队列快照核对，请结合核对问题查看"
        except ExportError as error:
            end_warning = "未能复核结束时的批次状态：" + str(error)
        counts = Counter(entry["result_status"] for entry in rows)
        settings = object_value(batch.get("settings"))
        summary = {
            "exported_at": datetime.now(timezone.utc).isoformat(), "package": PACKAGE,
            "batch_id": batch["batch_id"], "batch_status": text(batch.get("status")),
            "source_mode": text(settings.get("source_mode")), "date_range": text(settings.get("date_range")),
            "requested_count": settings.get("limit", len(rows)), "queue_item_count": len(rows),
            "matched_record_count": len(records),
            "result_counts": {key: counts[key] for key in ("complete", "partial", "failed", "pending")},
            "queue_status_counts": dict(Counter(text(entry["item"].get("status")) or "unknown" for entry in rows)),
            "record_status_counts": dict(Counter(text(record.get("status")) or "unknown" for record in records)),
            "sku_count": len(skus), "complete_quote_count": sum(row["报价完整"] == "是" for row in skus),
            "complete_quote_definition": "同一规格同时具备非空、非负整数分单位的券前价与券后价；未知价格不计为零",
            "images_requested": arguments.images, "exported_image_count": len(set(exported.values())),
            "image_reference_status_counts": dict(Counter(entry["status"] for entry in manifest)),
            "snapshot_warning": end_warning,
            "issues": [{"index": entry["index"], "record_id": text(entry["item"].get("record_id")), "messages": entry["issues"]}
                       for entry in rows if entry["issues"]],
            "counting_note": "complete/partial/failed/pending 按本批逐件队列与匹配记录核对；批次 complete 不等于所有商品完整采集成功。",
        }
        write_json(output, "batch.json", batch)
        write_json(output, "records.json", records)
        write_json(output, "summary.json", summary)
        write_csv(output, "products.csv", PRODUCT_FIELDS, products)
        write_csv(output, "skus.csv", SKU_FIELDS, skus)
        if arguments.images:
            write_json(output, "image_manifest.json", manifest)
        print("导出目录：" + str(output))
        print("当前批次 %s：%d 件队列，%d 条匹配记录；complete %d，partial %d，failed %d，pending %d。" %
              (batch["batch_id"], len(rows), len(records), counts["complete"], counts["partial"], counts["failed"], counts["pending"]))
        print("规格 %d 个，完整报价 %d 个；导出 PNG %d 张。" % (len(skus), summary["complete_quote_count"], summary["exported_image_count"]))
        if end_warning:
            print(end_warning, file=sys.stderr)
        if summary["issues"]:
            print("存在记录核对问题，详见 summary.json 和 products.csv。", file=sys.stderr)
        image_errors = any(entry["status"] in {"error", "rejected"} for entry in manifest)
        return 1 if summary["issues"] or end_warning or image_errors else 0
    except (ExportError, OSError, UnicodeError) as error:
        print("导出未完成：" + str(error), file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
