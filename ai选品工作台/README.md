# AI选品工作台

基于 PDD Parser Contributors 的前端代码。原许可见 [LICENSE](LICENSE)。

## 本地开发

```powershell
npm.cmd ci
npm.cmd run dev
```

开发地址为 http://localhost:3000。商品、采集和选品任务沿用 PDD Parser 后端接口。开发服务默认把 `/api` 转发到 `http://127.0.0.1:5000`。如果后端运行在其他地址，在启动前端前设置 `PDD_PARSER_API_TARGET`。

运行 `npm.cmd run build` 生成 `dist`。部署时需要为前端路由提供 HTML 回退，并将 `/api` 转发到后端。

## 抖音采集入库

使用 Node.js 24。在「手机商品采集 → 结果与回传」导出 JSON，然后在本地工作台打开「商品库 → 抖音」，选择「导入手机采集 JSON」。也可以导入电脑版批次导出的 `records.json`。

记录写入 `data/douyin.sqlite`。同一采集记录再次导入时，只接受更高的修订版本。不同采集时间的记录独立保留。使用 `WORKBENCH_DOUYIN_DB` 可指定测试库路径。

抖音入库接口由本机 Vite 开发及预览服务提供。仅静态发布 `dist` 时，需要另行部署入库服务。

## 手机采集任务

工作台的「手机采集」可以给已配对的手机下发抖音采集任务。手机接单后会按考古加的类目、周期和数量执行，采到的商品逐件进入「商品库 → 抖音」。任务页显示采集进度、失败和入库数量。

考古加类目可搜索或逐级选择，允许选择父类目和「不限」。在「新建采集任务」中点击「更新类目」，工作台会从考古加读取并保存最新目录。手机 App 联网打开后同步目录。已下发的任务继续使用创建时的类目路径。首次构建时的备用类目表可用 `node scripts/sync-kaogu-categories.mjs` 更新。

1. 在电脑运行工作台，保持它监听 `127.0.0.1:3000`。让手机和电脑接入同一局域网。
2. 在另一个 PowerShell 7 窗口进入工作台目录，运行以下命令。将地址换成当前电脑在局域网中的 IPv4。首次运行需要命令行里的 OpenSSL，会生成该地址专用的 HTTPS 证书。

   ```powershell
   .\scripts\start-mobile-gateway.ps1 -Address '192.168.110.11'
   ```

3. 从启动输出复制服务器地址和证书 SHA-256 到手机 App 的「工作台接单」。在网页「手机采集」生成配对码，在手机填入并配对。工作台可通过启动环境中的 `VITE_MOBILE_GATEWAY_URL` 和 `VITE_MOBILE_GATEWAY_FINGERPRINT` 显示这两个值。
4. 保持手机解锁、无障碍已启用，考古加和抖音都已登录。进入「待命接单」后，从网页选择设备并下发任务。切出待命页或遇到锁屏、登录提示时，在手机上处理后继续。

HTTPS 入口只转发手机配对、领取任务、进度和结果。配对后每台设备使用自己的凭证。同一任务重复领取时沿用手机保存的批次，服务器按任务和记录编号核算已入库数。

### 部署到服务器

工作台可部署在现有 HTTPS 站点的 `/ai-selection/` 下。构建前将 `VITE_MOBILE_GATEWAY_URL` 设为服务器的 HTTPS 地址（只含主机和端口），使用私有证书时同时设置 `VITE_MOBILE_GATEWAY_FINGERPRINT`。

```sh
npm ci
npm run build -- --base /ai-selection/
npm run build:server
```

将 `dist/` 放到发布目录的 `web/ai-selection/`，将 `dist-server/production.js` 放到 `backend/production.mjs`。部署配置在 [scripts/deploy](scripts/deploy)，其中的 Nginx 片段沿用现有站点的 `/api/access` 登录校验。服务端设置 `WORKBENCH_WEB_ORIGIN` 为浏览器访问的 HTTPS 源，并将 `WORKBENCH_DOUYIN_DB` 指向发布目录之外的持久化路径。拼多多与其他沿用 PDD Parser 的接口需要独立部署对应后端。

已连接本机的手机可在「工作台接单」中重新配对。服务器下发的新任务会把结果回传到服务器商品库。

### 公网入口与本地存储

服务器可以继续提供网页、登录和手机访问地址，通过 SSH 把工作台请求转给 Windows 电脑。此模式下，采集记录和原图写入电脑的 `data/douyin.sqlite`。安装在电脑上的 Chrome 会在新记录入库后自动尝试补齐轮播图。

先构建后端。在工作台目录用已有的中继账号和密钥启动：

```powershell
npm run build:server
.\scripts\start-local-relay.ps1 `
  -SshTarget ai-selection-relay@your-server `
  -IdentityFile (Join-Path $env:USERPROFILE '.ssh\id_ed25519_ai_selection_relay') `
  -WebOrigin 'https://your-server:10443'
```

中继账号仅用于本机回环地址的反向转发。服务器的工作台代理应指向中继端口 `127.0.0.1:18311`，关闭请求和响应缓冲。电脑需保持开机，启动脚本在连接断开后会重连。手机可继续使用原公网地址。

## 拼多多百亿补贴手机采集

在「手机采集」选择「拼多多」，生成设备密钥，在拼多多采集 App 中填入手机可访问的采集服务地址和密钥。工作台可以下发任务，按页面日销或预估日销售额筛选结果，导出 CSV，预览和下载已回传的商品主图。

在运行工作台后端的主机上设置 `PDD_MOBILE_API_TARGET`（该主机可访问的采集服务地址，默认 `http://127.0.0.1:8765`）和 `PDD_ADMIN_PASSWORD`（采集服务管理员口令）。设备配对、历史任务和图片沿用原采集服务。共享部署时，反向代理须对 `/api/workbench/pdd-mobile/` 校验管理员身份；手机继续使用原有的设备接口。
