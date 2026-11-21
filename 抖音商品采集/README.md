# 抖音商品采集

考古加商品列表 → 商品详情和关联视频 → 真机抖音商城扫码读取规格、报价和规格图 → 按商品 ID 合并。

## 准备

- 在这个目录打开 PowerShell，使用 Python 3.12 运行 `python -m pip install -r requirements.txt`。
- 考古加使用已登录的 Chrome。默认用户目录为 `D:\ChromeDebug\User Data`，本地调试端口为 `9223`。如果该窗口还未启用调试端口，正常关闭它后用下面的命令重新打开并登录。
- 真机扫码使用已登录的抖音商城 App、通过 USB 授权的 Android 手机及 Android SDK 的 ADB，当前脚本支持 1080 × 2400 的采集画面。
- 模拟器站内搜索使用 Node.js 和 Windows PowerShell 简体中文 OCR。

```powershell
& 'C:\Program Files\Google\Chrome\Application\chrome.exe' '--user-data-dir=D:\ChromeDebug\User Data' '--remote-debugging-port=9223' 'https://www.kaogujia.com/darenSquare/productList'
```

## 真机自动采集

在当前脚本目录运行，`<设备序列号>` 用 `adb devices` 中已授权的 USB 手机序列号替换。新任务默认使用此前保存的筛选条件，关键词为「吹风机」，取第 1 页 50 条，`period=1`、`sort_field=sales`、`sort=0`。指定目录的 Chrome 窗口已经打开时，可传入该窗口的本机调试端口，例如 `--port 9223`。

```powershell
python -X utf8 .\run_mall_page.py --serial "<设备序列号>"
```

可用 `--keyword`、`--period`、`--page`、`--limit`、`--sort-field` 和 `--sort` 覆盖筛选。续跑时提供原结果目录并加 `--resume`，脚本沿用原名单。

规格采集使用小图模式。脚本会逐款核对选中状态，读取顶部展示价和优惠价，再点击该款顶部图片，保存完整预览后返回。报价保留页面上的「券后价」「补贴价」等原标签，图片与规格组合一起保存。商品入口显示的「共几款」会与实际采集条数一并记录。

## 分步运行

下面以考古加的吹风机关键词、第一页、每页 50 条为例。每次新采集使用独立目录。第三步选择模拟器里的商品规格并读取界面显示的价格。

```powershell
$run = Join-Path '..\采集结果\抖音' ('考古加-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
$page = Join-Path $run '商品列表.json'
$assets = Join-Path $run '考古加详情素材.json'
$quotes = Join-Path $run '抖音规格报价.json'

python -X utf8 .\kaogujia_page.py $page --port 9223 --keyword '吹风机' --period 1 --page 1 --limit 50 --sort-field sales --sort 0
python -X utf8 .\kaogujia_assets.py $page $assets --port 9223
python -X utf8 -m douyin.collect $page $quotes
python -X utf8 .\merge_results.py $page --browser-assets $assets --app-results $quotes --output-dir $run
```

第三步加 `--resume` 会跳过已有记录。重测失败行时，可在同一个列表文件和输出文件上加 `--resume --start 1 --end 5`，把行号换成需要重测的范围。每份模拟器记录都区分商品核对、规格价格和图片结果。

可以用 `python -X utf8 -m douyin.collect $page --validate-only` 离线检查列表。

## 输出

- `商品列表.json` 保存考古加商品键、抖音商品 ID、标题、店铺和商品链接。
- `考古加详情素材.json` 记录主图文件、商品主视频和关联推广视频。能确认分享链接时会单独保存；视频文件以实际取得的媒体为准。
- `抖音规格报价.json` 记录券前价、券后价、规格图及受阻原因。价格单位为分。
- `合并采集结果.json` 和 `采集实测报告.md` 汇总商品、素材和规格结果。

历史的一页吹风机样本已确认 50 张考古加主图和 282 条关联视频链接。最近一轮模拟器整页测试仍受搜索入口、加载或商品身份核对影响，逐款报价和图片需要在实际页面核实。
