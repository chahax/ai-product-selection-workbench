from __future__ import annotations

import argparse
from collections import Counter
from decimal import Decimal
import json
from pathlib import Path
import re
import unicodedata
from urllib.parse import parse_qs, urlsplit, urlunsplit

MARKET = "https://www.kaogujia.com"
DETAIL_ROUTE = "/productDetails/productOverview/"
DOUYIN_ROUTE = "/ecommerce/trade/detail/index.html"
VERIFIED = {"verified_product", "sku_checked", "complete"}
STATUS = {
    "verified_product": "已核对商品",
    "sku_checked": "已核对规格",
    "complete": "已完成",
    "opened_unverified": "已打开，身份待核对",
    "opened_ui_unreadable": "已打开，页面无法读取",
    "douyin_product_page_network_error": "抖音商品页网络异常",
    "blocked": "采集受阻",
    "app_unresponsive": "抖音应用无响应",
    "not_attempted": "待测试",
    "not_in_report": "未取得测试记录",
}

def unpack(path):
    if path is None:
        return {}, []
    document = json.loads(path.read_text(encoding="utf-8-sig"))
    if isinstance(document, list):
        return {}, document
    for name in ("items", "rows", "results", "products"):
        if isinstance(document.get(name), list):
            return document, document[name]
    raise ValueError(f"Unreadable report structure: {path}")

def clean_title(value):
    return re.sub(r"[\W_]+", "", unicodedata.normalize("NFKC", str(value or "")).casefold())

def identity(row, fallback=None):
    detail = urlsplit(row.get("detail_link") or row.get("detail_url") or "")
    raw_kaogu = row.get("kaogujia_key") or row.get("sku_key") or row.get("kaogu_key") or ""
    if detail.hostname == "www.kaogujia.com" and detail.path.startswith("/productDetails/"):
        raw_kaogu = raw_kaogu or detail.path.rstrip("/").rsplit("/", 1)[-1]
    if not re.fullmatch(r"[a-zA-Z0-9]{8,24}", str(raw_kaogu)):
        raw_kaogu = None
    jump = urlsplit(row.get("url") or row.get("douyin_url") or row.get("product_url") or "")
    raw_douyin = row.get("douyin_product_id") or row.get("product_id") or row.get("id") or ""
    if jump.hostname == "haohuo.jinritemai.com" and jump.path == DOUYIN_ROUTE:
        url_id = parse_qs(jump.query).get("id", [""])[0]
        if raw_douyin and raw_douyin != url_id and str(raw_douyin).isdigit():
            raise ValueError("Douyin product ID and URL disagree")
        raw_douyin = url_id
    raw_douyin = str(raw_douyin) if re.fullmatch(r"\d{10,25}", str(raw_douyin)) else None
    label = row.get("title") or row.get("title_expected") or row.get("name")
    return {"order": int(row.get("order") or row.get("index") or fallback or 0),
            "title": label, "kaogujia_key": raw_kaogu, "douyin_product_id": raw_douyin}

def select(current, candidates, claimed):
    matches = []
    for pos, (candidate, key) in enumerate(candidates):
        if pos in claimed:
            continue
        known = [(current[f], key[f]) for f in ("kaogujia_key", "douyin_product_id")
                 if current[f] and key[f]]
        if any(a != b for a, b in known):
            continue
        if known:
            matches.append((pos, candidate, key, "stable_id"))
        elif current["order"] == key["order"]:
            title = clean_title(current["title"])
            if title and title == clean_title(key["title"]):
                matches.append((pos, candidate, key, "exact_title_and_order"))
    precise = [match for match in matches if match[3] == "stable_id"]
    applicable = precise or matches
    if len(applicable) != 1:
        return {}, {}, ("ambiguous" if applicable else None)
    pos, item, key, method = applicable[0]
    claimed.add(pos)
    return item, key, method

def pub_asset_url(value):
    if not isinstance(value, str):
        return None
    url = urlsplit(value)
    if url.scheme != "https" or not url.hostname or url.username or url.password:
        return None
    if any(re.search("auth|token|cookie|session|sign|secret|credential", field, re.I)
           for field in parse_qs(url.query)):
        return None
    return urlunsplit(url._replace(fragment=""))

def media_entries(value, base, directory, source):
    result = []
    named = set()
    for entry in (value if isinstance(value, list) else [value]):
        if isinstance(entry, str):
            entry = {("url" if entry.startswith("https://") else "path"): entry}
        if not isinstance(entry, dict):
            continue
        name = entry.get("path") or entry.get("file") or entry.get("local_path")
        path = None
        if name:
            tests = [base / name]
            if directory:
                tests.insert(0, base / directory / name)
            path = next((str(p.resolve()) for p in tests if p.is_file() and p.stat().st_size > 0), None)
        url = pub_asset_url(entry.get("url") or entry.get("source_url"))
        if not path and not url:
            continue
        if (path or url) in named:
            continue
        named.add(path or url)
        result.append({key: item for key, item in {
            "file": path, "url": url, "source": entry.get("source") or source,
            "width": entry.get("width"), "height": entry.get("height"), "title": entry.get("title"),
        }.items() if item is not None})
    return result

def detail_group(row, base):
    asset = row.get("assets")
    if isinstance(asset, dict):
        row = {**row, **asset}
    directory = row.get("directory") or row.get("output_dir")
    main = row.get("main_images") or row.get("main_image") or row.get("main_image_url")
    video = row.get("videos") or row.get("video") or row.get("video_url") or row.get("video_urls")
    related = row.get("related_videos") or row.get("related_video")
    return {
        "status": row.get("detail_status") or row.get("asset_status") or row.get("status"),
        "main_images": media_entries(main, base, directory, "考古加商品详情"),
        "videos": media_entries(video, base, directory, "考古加商品详情"),
        "related_videos": media_entries(related, base, directory, "考古加关联视频"),
        "main_image_status": row.get("main_image_status"),
        "product_video_status": row.get("product_video_status"),
        "related_video_status": row.get("related_video_status"),
        "notes": row.get("asset_note") or row.get("detail_note"),
    }

def normalized_fen(value):
    return value if type(value) is int and value >= 0 else None

def douyin_group(row, base, association):
    status = row.get("status") or "not_in_report"
    variants = []
    verified = status in VERIFIED
    sku_count = row.get("sku_count")
    if not verified or type(sku_count) is not int or sku_count < 0:
        sku_count = None
    sku_count_source = row.get("sku_count_source")
    tags = row.get("product_tags")
    if verified and isinstance(tags, list):
        tags = list(dict.fromkeys(tag.strip() for tag in tags if isinstance(tag, str) and tag.strip()))
    else:
        tags = []
    if verified:
        all_skus = row.get("skus") or row.get("variants") or []
        for raw in all_skus:
            labels = raw.get("specifications")
            unit = {
                "name": raw.get("name") or raw.get("spec_name") or raw.get("label"),
                "specifications": labels if isinstance(labels, dict) else None,
                "price_before_coupon_fen": normalized_fen(raw.get("price_before_coupon_fen")),
                "price_after_coupon_fen": normalized_fen(raw.get("price_after_coupon_fen")),
                "price_before_label": raw.get("price_before_label"),
                "price_after_label": raw.get("price_after_label"),
                "hot": raw.get("hot") if isinstance(raw.get("hot"), bool) else None,
                "images": media_entries(raw.get("images") or raw.get("image") or raw.get("image_path"),
                                       base, row.get("directory"), "抖音规格大图"),
            }
            variants.append(unit)
    return {
        "status": status, "identity_verified": verified, "row_association": association,
        "matched_by": row.get("matched_by"), "tested_at": row.get("tested_at"),
        "search_submitted": row.get("search_submitted"),
        "reason": row.get("reason"), "search_reason": row.get("search_reason"),
        "sku_note": row.get("sku_note"), "sku_image_note": row.get("sku_image_note"),
        "sku_count": sku_count,
        "sku_count_source": sku_count_source if sku_count is not None and isinstance(sku_count_source, str) else None,
        "product_tags": tags,
        "variants": variants,
        "page_prices": row.get("page_prices") if verified else None,
    }

def merge(page, browser, app):
    source, source_rows = unpack(page)
    _, web_rows = unpack(browser)
    _, app_rows = unpack(app)
    assets = [(item, identity(item, pos)) for pos, item in enumerate(web_rows, 1)]
    tests = [(item, identity(item, pos)) for pos, item in enumerate(app_rows, 1)]
    browser_used, app_used = set(), set()
    output = []
    metrics = Counter()
    states = Counter()
    for index, from_page in enumerate(source_rows, 1):
        key = identity(from_page, index)
        detail_row, detail_identity, method = select(key, assets, browser_used)
        detail = detail_group(detail_row or from_page, (browser if detail_row else page).parent)
        for field in ("kaogujia_key", "douyin_product_id"):
            key[field] = key[field] or detail_identity.get(field)
        app_row, app_identity, association = select(key, tests, app_used)
        result = douyin_group(app_row, (app or page).parent, association)
        key["douyin_product_id"] = key["douyin_product_id"] or app_identity.get("douyin_product_id")
        detail_key = key["kaogujia_key"]
        if detail_key:
            key["kaogujia_detail_url"] = MARKET + DETAIL_ROUTE + detail_key
        key["shop"] = (from_page.get("shop_name") or from_page.get("shop") or
                       detail_row.get("shop_name") or detail_row.get("shop") or app_row.get("shop"))
        detail["row_association"] = method
        output.append({**key, "kaogujia": detail, "douyin": result})
        metrics["listed"] += 1
        metrics["douyin_records"] += bool(app_row)
        submitted = app_row.get("search_submitted")
        if type(submitted) is bool:
            metrics["search_submission_records"] += 1
            metrics["searches_submitted"] += submitted
        metrics["identity_verified"] += result["identity_verified"]
        for kind, prefix in (("main_images", "main"), ("videos", "video"), ("related_videos", "related_video")):
            media = detail[kind]
            metrics[prefix + "_goods"] += bool(media)
            metrics[prefix + "_files_goods"] += any(item.get("file") for item in media)
            metrics[prefix + "_files"] += sum(bool(item.get("file")) for item in media)
        metrics["spec_goods"] += bool(result["variants"])
        for sku in result["variants"]:
            metrics["specs"] += 1
            metrics["specs_with_both_prices"] += (
                sku["price_before_coupon_fen"] is not None and sku["price_after_coupon_fen"] is not None
            )
            metrics["specs_with_images"] += any(item.get("file") for item in sku["images"])
        states[result["status"]] += 1
    return {
        "source_page": source.get("source"),
        "query": source.get("query"), "filters": source.get("filters"),
        "coverage": dict(metrics), "douyin_status_counts": dict(states),
        "unassociated_browser_results": len(assets) - len(browser_used),
        "unassociated_douyin_results": len(tests) - len(app_used),
        "items": output,
    }

def money(value):
    return "—" if value is None else f"¥{Decimal(value) / 100:,.2f}"

def label(value):
    return re.sub(r"\s+", " ", str(value or "")).strip().replace("|", "\\|")

def links(values, prefix):
    result = []
    for ordinal, entry in enumerate(values, 1):
        target = entry.get("file") or entry.get("url")
        display = prefix + (str(ordinal) if len(values) > 1 else "")
        if not entry.get("file"):
            display += "链接"
        if entry.get("file"):
            target = Path(target).as_posix()
        result.append(f"[{display}](<{target}>)")
    return " · ".join(result)

def render(report):
    c = Counter(report["coverage"])
    lines = [
        "# 考古加当前页商品采集实测", "",
        f"共 {c['listed']} 件，抖音采集记录覆盖 {c['douyin_records']} 件，核对商品身份 {c['identity_verified']} 件。",
        f"考古加详情主图覆盖 {c['main_goods']} 件，其中 {c['main_files_goods']} 件已保存文件。"
        f"商品主视频覆盖 {c['video_goods']} 件，其中 {c['video_files_goods']} 件已保存文件。",
        f"关联推广视频覆盖 {c['related_video_goods']} 件，其中 {c['related_video_files_goods']} 件已保存文件。",
        f"抖音规格 {c['specs']} 款，{c['specs_with_both_prices']} 款有双价，{c['specs_with_images']} 款有本地规格图。",
        "", "## 商品覆盖", "",
        "| 行 | 商品 | 主图 | 商品视频 | 关联视频 | 抖音状态 | 规格 |",
        "| ---: | --- | ---: | ---: | ---: | --- | ---: |",
    ]
    for item in report["items"]:
        web, app = item["kaogujia"], item["douyin"]
        details = item.get("kaogujia_detail_url")
        title = label(item["title"] or "未取得商品名")
        if details:
            title = f"[{title}]({details})"
        status = app["status"]
        lines.append(
            f"| {item['order']} | {title} | {len(web['main_images'])} | {len(web['videos'])} | {len(web['related_videos'])} | "
            f"{STATUS.get(status, label(status))} | {len(app['variants'])} |"
        )
    for item in report["items"]:
        lines.extend(["", f"## {item['order']}. {label(item['title'])}", ""])
        web, app = item["kaogujia"], item["douyin"]
        for media, name in (("main_images", "主图"), ("videos", "商品主视频"), ("related_videos", "关联推广视频")):
            files = links(web[media], name)
            lines.append(f"{name} {files or '未取到'}")
        if app["product_tags"]:
            lines.extend(["", "商品标签 " + " · ".join(label(tag) for tag in app["product_tags"])])
        if app.get("sku_count") is not None:
            lines.extend(["", f"商品页显示 {app['sku_count']} 款，已采集 {len(app['variants'])} 个规格组合。"])
        video_state = {
            "no_product_video_in_detail": "详情未显示商品主视频",
            "empty_current_period": "查询时间内关联视频为空",
            "metadata_only": "关联视频已记录分享链接",
        }
        messages = [m for m in (web.get("notes"), video_state.get(web.get("product_video_status")),
                                video_state.get(web.get("related_video_status")),
                                app.get("reason"), app.get("search_reason"),
                                app.get("sku_note"), app.get("sku_image_note")) if m]
        if messages:
            lines.extend(["", " · ".join(label(m) for m in messages)])
        if app["variants"]:
            lines.extend(["", "| 规格 | 优惠前价格 | 优惠后价格 | 热销 | 规格图片 |", "| --- | ---: | ---: | --- | --- |"])
            for sku in app["variants"]:
                title = sku["name"] or " / ".join(f"{k} {v}" for k,v in (sku["specifications"] or {}).items())
                before, after = money(sku["price_before_coupon_fen"]), money(sku["price_after_coupon_fen"])
                if sku["price_before_label"] and before != "—":
                    before = f"{label(sku['price_before_label'])} {before}"
                if sku["price_after_label"] and after != "—":
                    after = f"{label(sku['price_after_label'])} {after}"
                lines.append(f"| {label(title)} | {before} | {after} | {'热销' if sku['hot'] else '—'} | "
                             f"{links(sku['images'], '规格图') or '—'} |")
    return "\n".join(lines) + "\n"

def main():
    parser = argparse.ArgumentParser(description='按商品 ID 合并考古加素材和抖音规格报价')
    parser.add_argument("page", type=Path)
    parser.add_argument("--browser-assets", type=Path)
    parser.add_argument("--app-results", type=Path)
    parser.add_argument("--output-dir", type=Path, required=True)
    cli = parser.parse_args()
    report = merge(cli.page.resolve(), cli.browser_assets.resolve() if cli.browser_assets else None,
                   cli.app_results.resolve() if cli.app_results else None)
    cli.output_dir.mkdir(parents=True, exist_ok=True)
    (cli.output_dir / "合并采集结果.json").write_text(json.dumps(report, ensure_ascii=False, indent=2)+"\n", encoding="utf-8")
    (cli.output_dir / "采集实测报告.md").write_text(render(report), encoding="utf-8")
    print(json.dumps(report["coverage"]))

if __name__ == "__main__":
    main()
