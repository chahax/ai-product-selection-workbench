from __future__ import annotations

import argparse
import base64
import json
from pathlib import Path
import subprocess
import sys
import time
from urllib.parse import parse_qsl, urlencode, urlsplit, urlunsplit
from urllib.request import urlopen

import websocket


PROFILE = Path(r"D:\ChromeDebug\User Data")
CHROME = Path(r"C:\Program Files\Google\Chrome\Application\chrome.exe")
PRODUCT_LIST = "https://www.kaogujia.com/darenSquare/productList"
SEARCH_API = "https://service.kaogujia.com/api/sku/search"

READ_ROWS = r"""
(() => {
  const tableRow = document.querySelector("tr.el-table__row");
  const data = tableRow?.__vueParentComponent?.props?.store?.states?.data?.value;
  const rows = Array.isArray(data) ? data.flatMap(row => {
    if (!row.title || typeof row.product_id !== "string") return [];
    let productUrl = null;
    let productId = null;
    try {
      const url = new URL(row.product_url);
      const id = url.searchParams.get("id");
      if (url.origin === "https://haohuo.jinritemai.com" &&
          url.pathname === "/ecommerce/trade/detail/index.html" &&
          id && /^\d{10,25}$/.test(id)) {
        productUrl = url.href;
        productId = id;
      }
    } catch {}
    return [{
      title: row.title,
      shop_name: row.shop_name || null,
      kaogujia_key: row.product_id,
      product_id: productId,
      url: productUrl,
      list_image_url: row.cover?.startsWith("https://") ? row.cover : null,
      detail_link: location.origin + "/productDetails/productOverview/" +
        encodeURIComponent(row.product_id)
    }];
  }) : [];
  return { location: location.origin + location.pathname, rows };
})()
"""


class ChromeConnection:
    def __init__(self, endpoint, query, filters):
        self.socket = websocket.create_connection(endpoint, timeout=10, suppress_origin=True)
        self.sequence = 0
        self.query = query
        self.filters = filters
        self.requests = {}
        self.searches = {}
        self.patch_error = None

    def send(self, method, params=None, session=None):
        self.sequence += 1
        call = {"id": self.sequence, "method": method, "params": params or {}}
        if session:
            call["sessionId"] = session
        self.socket.send(json.dumps(call))
        return self.sequence

    def call(self, method, params=None, session=None):
        call_id = self.send(method, params, session)
        while True:
            response = json.loads(self.socket.recv())
            if response.get("id") == call_id:
                if "error" in response:
                    raise RuntimeError(f"Chrome 调试命令失败（{method}）")
                return response["result"]
            self.observe(response)

    def observe(self, event):
        method = event.get("method")
        params = event.get("params", {})
        if method == "Fetch.requestPaused":
            self.continue_search(params, event.get("sessionId"))
        elif method == "Network.requestWillBeSent":
            request = params["request"]
            if request["method"] == "POST" and request["url"].split("?")[0] == SEARCH_API:
                self.requests[params["requestId"]] = True
        elif method == "Network.responseReceived" and params["requestId"] in self.requests:
            response = params["response"]
            self.searches[params["requestId"]] = int(response["status"])

    def continue_search(self, paused, session):
        request = paused["request"]
        overrides = {"requestId": paused["requestId"]}
        if request["method"] == "POST" and request["url"].split("?")[0] == SEARCH_API:
            try:
                if self.query:
                    address = urlsplit(request["url"])
                    query = dict(parse_qsl(address.query, keep_blank_values=True))
                    query.update(self.query)
                    overrides["url"] = urlunsplit(address._replace(query=urlencode(query)))
                if self.filters:
                    original = json.loads(request.get("postData", ""))
                    if not isinstance(original, dict):
                        raise ValueError("unexpected body")
                    payload = json.dumps({**original, **self.filters}, ensure_ascii=False).encode("utf-8")
                    overrides["postData"] = base64.b64encode(payload).decode("ascii")
            except ValueError:
                self.patch_error = "商品列表请求格式已变化，筛选参数未提交。"
                overrides = {"requestId": paused["requestId"]}
        self.send("Fetch.continueRequest", overrides, session)

    def close(self):
        self.socket.close()


def start_chrome(profile):
    if not profile.is_dir():
        raise RuntimeError(f"找不到 Chrome 用户目录 {profile}")
    port_file = profile / "DevToolsActivePort"
    previous_stamp = port_file.stat().st_mtime_ns if port_file.exists() else None
    flags = [
        str(CHROME), "--headless=new", "--remote-debugging-port=0",
        "--remote-debugging-address=127.0.0.1", f"--user-data-dir={profile}",
        "--no-first-run", "--no-default-browser-check", "--window-size=1500,950", "about:blank",
    ]
    process = subprocess.Popen(
        flags, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
        creationflags=subprocess.CREATE_NO_WINDOW,
    )
    deadline = time.monotonic() + 12
    while time.monotonic() < deadline and process.poll() is None:
        if port_file.exists() and port_file.stat().st_mtime_ns != previous_stamp:
            port, path, *_ = port_file.read_text(encoding="utf-8").splitlines()
            return process, f"ws://127.0.0.1:{int(port)}{path}"
        time.sleep(0.2)
    if process.poll() is None:
        process.terminate()
    raise RuntimeError("无法启动指定的 Chrome 用户目录。若该窗口已打开，请用 --port 连接。")


def capture_page(connection):
    target = connection.call("Target.createTarget", {"url": "about:blank"})["targetId"]
    try:
        session = connection.call("Target.attachToTarget", {"targetId": target, "flatten": True})["sessionId"]
        connection.call("Network.enable", {"maxPostDataSize": 65536}, session)
        if connection.filters or connection.query:
            connection.call(
                "Fetch.enable", {"patterns": [{"urlPattern": SEARCH_API + "*", "requestStage": "Request"}]}, session,
            )
        connection.call("Page.navigate", {"url": PRODUCT_LIST}, session)

        deadline = time.monotonic() + 25
        stable_since = None
        previous_count = 0
        while time.monotonic() < deadline:
            evaluated = connection.call("Runtime.evaluate", {"expression": READ_ROWS, "returnByValue": True}, session)
            observed = evaluated.get("result", {}).get("value", {})
            if connection.patch_error:
                raise RuntimeError(connection.patch_error)
            statuses = list(connection.searches.values())
            if any(status in (401, 403) for status in statuses) or observed.get("location", "").endswith("/loginPage"):
                raise RuntimeError("当前 Chrome 用户目录的考古加会话未登录或已过期，请在该目录对应的窗口登录。")
            rows = observed.get("rows", [])
            if 200 in statuses and rows:
                if len(rows) != previous_count or stable_since is None:
                    previous_count = len(rows)
                    stable_since = time.monotonic()
                expected_count = connection.query.get("limit")
                if len(rows) == expected_count or time.monotonic() - stable_since >= 3:
                    return [{"order": index, **row} for index, row in enumerate(rows, 1)]
            time.sleep(0.45)
        raise RuntimeError("没有读到商品列表，请核对筛选参数或在 Chrome 确认商品库能加载。")
    finally:
        connection.call("Target.closeTarget", {"targetId": target})


def main():
    parser = argparse.ArgumentParser(description="使用指定 Chrome 登录会话读取考古加商品库一页")
    parser.add_argument("output", type=Path, help="商品页 JSON 文件")
    parser.add_argument("--profile", type=Path, default=PROFILE)
    parser.add_argument("--port", type=int, help="已经使用同一用户目录启动的 Chrome 调试端口")
    parser.add_argument("--keyword")
    parser.add_argument("--period", type=int)
    parser.add_argument("--page", type=int)
    parser.add_argument("--limit", type=int)
    parser.add_argument("--sort-field")
    parser.add_argument("--sort", type=int, choices=[0, 1])
    args = parser.parse_args()
    if any(value is not None and value < 1 for value in [args.page, args.limit]):
        parser.error("页码和每页条数必须大于零。")
    query = {key: getattr(args, key) for key in ("limit", "page", "sort_field", "sort") if getattr(args, key) is not None}
    filters = {key: getattr(args, key) for key in ("period", "keyword") if getattr(args, key) is not None}
    process = None
    connection = None
    try:
        if args.port:
            with urlopen(f"http://127.0.0.1:{args.port}/json/version", timeout=4) as response:
                endpoint = json.load(response)["webSocketDebuggerUrl"]
        else:
            process, endpoint = start_chrome(args.profile)
        connection = ChromeConnection(endpoint, query, filters)
        rows = capture_page(connection)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        result = {"source": PRODUCT_LIST, "query": query, "filters": filters, "items": rows}
        args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(f"已读取 {len(rows)} 条商品 · {args.output}")
        return 0
    except (RuntimeError, OSError, websocket.WebSocketException) as error:
        print(f"采集未完成 · {error}", file=sys.stderr)
        return 1
    finally:
        if connection:
            if process and process.poll() is None:
                try:
                    connection.call("Browser.close")
                except (RuntimeError, OSError, websocket.WebSocketException):
                    pass
            connection.close()
        if process and process.poll() is None:
            try:
                process.wait(timeout=4)
            except subprocess.TimeoutExpired:
                process.terminate()


if __name__ == "__main__":
    sys.exit(main())
