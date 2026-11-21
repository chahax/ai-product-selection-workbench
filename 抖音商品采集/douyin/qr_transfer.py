from __future__ import annotations

import argparse
import hashlib
import json
import re
import shlex
import subprocess
import time
from pathlib import Path
from uuid import uuid4


MEDIA = "content://media/external_primary/images/media"
QR_NAME = re.compile(r"Codex-Mall-QR-[a-f0-9]{32}\.png")
PNG = b"\x89PNG\r\n\x1a\n"


class DeviceAlbum:
    def __init__(self, adb, serial):
        self.serial = serial
        self.base = [str(adb), "-s", serial]

    def run(self, *args, check=True, timeout=8):
        return subprocess.run(self.base + list(args), check=check, capture_output=True, timeout=timeout)

    def shell(self, *words, check=True):
        return self.run("shell", shlex.join(words), check=check)

    def exists(self, target):
        result = self.shell("test", "-e", target, check=False)
        if result.returncode not in (0, 1) or result.stderr:
            raise RuntimeError("无法确认手机上的二维码文件")
        return result.returncode == 0

    def scan(self, target):
        self.shell("am", "broadcast", "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE",
                   "-d", "file://" + target)

    def records(self, name):
        selection = f"_display_name='{name}' AND relative_path='Pictures/'"
        result = self.shell("content", "query", "--uri", MEDIA, "--projection",
                            "_id:_display_name:relative_path", "--where", selection)
        response = result.stdout.decode("utf-8", "replace").strip()
        if response.startswith("No result found"):
            return []
        match_row = re.compile(rf"Row: \d+ _id=(\d+), _display_name={re.escape(name)}, relative_path=Pictures/")
        rows = [match_row.fullmatch(line.strip()) for line in response.splitlines()]
        if not rows or any(row is None for row in rows) or result.stderr:
            raise RuntimeError("手机相册索引无法读取或未返回唯一范围")
        return [row[1] for row in rows]

    def wait_records(self, name, *, present):
        until = time.monotonic() + 6
        while True:
            rows = self.records(name)
            if bool(rows) == present or time.monotonic() >= until:
                return rows
            time.sleep(0.4)

    def push(self, image, receipt, product_id=None):
        if not image.is_file() or image.stat().st_size > 8 * 1024 * 1024:
            raise ValueError("请选择 8 MiB 以内的二维码 PNG")
        payload = image.read_bytes()
        if not payload.startswith(PNG) or len(payload) > 8 * 1024 * 1024:
            raise ValueError("请选择 8 MiB 以内的二维码 PNG")
        if product_id is not None:
            import zxingcpp
            from PIL import Image
            from .android import qr_product_id
            with Image.open(image) as picture:
                codes = zxingcpp.read_barcodes(picture, formats=zxingcpp.BarcodeFormat.QRCode)
            if len(codes) != 1 or qr_product_id(codes[0].text) != product_id:
                raise ValueError("二维码商品 ID 与本次测试不一致")
        name = f"Codex-Mall-QR-{uuid4().hex}.png"
        target = "/sdcard/Pictures/" + name
        if self.exists(target):
            raise RuntimeError("专用二维码文件名已存在")
        meta = {"serial": self.serial, "filename": name, "sha256": hashlib.sha256(payload).hexdigest()}
        with receipt.open("x", encoding="utf-8") as handle:
            json.dump(meta, handle, ensure_ascii=False, indent=2)
        self.run("push", str(image), target, timeout=20)
        if self.run("exec-out", "cat", target).stdout != payload:
            raise RuntimeError("推送后的二维码字节校验失败，已保留清理凭据")
        try:
            self.scan(target)
            visible = bool(self.wait_records(name, present=True))
        except (RuntimeError, subprocess.SubprocessError):
            visible = False
        return {"device_file": target, "media_indexed": visible, "receipt": str(receipt)}

    def remove(self, receipt):
        meta = json.loads(receipt.read_text(encoding="utf-8"))
        if not isinstance(meta, dict):
            raise ValueError("清理凭据须为 JSON 对象")
        name = meta.get("filename", "")
        digest = meta.get("sha256", "")
        if (meta.get("serial") != self.serial or not isinstance(name, str) or not isinstance(digest, str)
                or not QR_NAME.fullmatch(name) or not re.fullmatch(r"[a-f0-9]{64}", digest)):
            raise ValueError("清理凭据与目标设备或专用二维码文件不一致")
        target = "/sdcard/Pictures/" + name
        if self.exists(target):
            copied = self.run("exec-out", "cat", target).stdout
            if hashlib.sha256(copied).hexdigest() != digest:
                raise RuntimeError("手机上的同名文件内容发生变化，已停止清理")
        try:
            indexed = self.records(name)
            if len(indexed) == 1:
                self.shell("content", "delete", "--uri", MEDIA + "/" + indexed[0])
        except (RuntimeError, subprocess.SubprocessError):
            pass
        if self.exists(target):
            self.shell("rm", "-f", target)
        removed = not self.exists(target)
        try:
            if removed and self.records(name):
                self.scan(target)
            index_removed = removed and not self.wait_records(name, present=False)
        except (RuntimeError, subprocess.SubprocessError):
            index_removed = None
        return {"device_file": target, "file_removed": removed, "media_entry_removed": index_removed}


def main(argv=None):
    parser = argparse.ArgumentParser(description="仅推送和移除本次测试专用的二维码 PNG")
    parser.add_argument("--serial", required=True, help="已授权的手机 ADB 序列号")
    parser.add_argument("--adb", type=Path)
    actions = parser.add_subparsers(dest="action", required=True)
    push = actions.add_parser("push")
    push.add_argument("png", type=Path)
    push.add_argument("--receipt", type=Path, required=True)
    push.add_argument("--product-id")
    remove = actions.add_parser("remove")
    remove.add_argument("--receipt", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        if args.adb is None:
            from .android import resolve_adb
            args.adb = resolve_adb()
        album = DeviceAlbum(args.adb, args.serial)
        if args.action == "push":
            outcome = album.push(args.png, args.receipt, args.product_id)
        else:
            outcome = album.remove(args.receipt)
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as exc:
        parser.exit(1, str(exc) + "\n")
    print(json.dumps(outcome, ensure_ascii=False))
    return 0 if args.action == "push" or outcome["file_removed"] and outcome["media_entry_removed"] is True else 1


if __name__ == "__main__":
    raise SystemExit(main())

