# AIStudio2API Android 启动器 (`:appgo`)

把 Go 后端 [Mag1cFall/AIStudio2API](https://github.com/Mag1cFall/AIStudio2API) 通过 PRoot + Ubuntu 24.04 arm64 跑在 Android 上，并让后端自带的浏览器（Camoufox）在手机上可见。

与同仓库的 `:app` 模块（Node 后端 `iBUHub/AIStudioToAPI`）互不影响，可同时安装：

| | `:app` | `:appgo` |
|---|---|---|
| 后端 | Node.js | Go（单文件静态二进制） |
| 端口 | 7860 | 2048 |
| 登录 | 后端自带 `/api/vnc/sessions` | 复用管理端「浏览器登录」，自动切到内置虚拟桌面 |
| applicationId | `com.aistudio.launcher` | `com.aistudio2api.launcher` |

## 构建

```bash
# 1. 复用 :app 的 Ubuntu rootfs，叠加 Go 后端二进制（默认取上游 latest release）
./scripts/build-rootfs-go.sh              # 指定版本: ./scripts/build-rootfs-go.sh v0.2.12
# 产物: appgo/src/main/assets/rootfs.tar.zst

# 2. 打包 APK
./gradlew :appgo:assembleDebug
# 产物: appgo/build/outputs/apk/debug/appgo-debug.apk
```

`build-rootfs-go.sh` 环境变量：`BASE_ROOTFS` / `BACKEND_BIN`（跳过下载）/ `OUT` / `WORK` / `ZSTD_LEVEL`。

## 运行目录

App 首次启动解压到 `filesDir/runtime/`：

```
runtime/
  lib/{libtalloc.so.2,libandroid-shmem.so}   proot 依赖
  tmp/                                        proot 临时目录
  dev/                                        app 私有 /dev
  rootfs/                                     Ubuntu 24.04 arm64
    opt/aistudio2api/
      aistudio2api                            后端二进制（static linux/arm64）
      VERSION                                 app 侧记录的版本号
      .env                                    app 生成的配置
      auth/<email>/                           账号
      runtime/                                日志、requests.db
    opt/camoufox/                             Camoufox（来自基础 rootfs）
    opt/novnc/                                虚拟桌面前端（noVNC）
```

## 关键文件

- `RuntimeEnv.java`：解压 rootfs、写 `.env`、拼 proot 命令（含 `DISPLAY=:99`）
- `ServerService.java`：前台服务跑 `./aistudio2api --open-ui=false`，同时拉起虚拟桌面，崩溃自动重启
- `MainActivity.java`：桌面版网页 + 「浏览器登录」自动切到虚拟桌面
- `PinchZoomLayout.java`：登录窗口的双指缩放容器（noVNC 会吞掉 WebView 自带的捏合）
- `UpdateChecker.java`：检查上游 release 并覆盖二进制
- `assets/scripts/start-aistudio2api.sh` / `vnc-serve.sh`
- `assets/novnc/login.html`：自动连接、按屏幕缩放、可手动发送文本的 noVNC 页面

## 功能

- **刷新页面**：菜单 → 刷新页面
- **检查原项目更新**：菜单 → 检查原项目更新 → 下载 `aistudio2api-vX.Y.Z-linux-arm64.tar.gz` → 覆盖 `/opt/aistudio2api/aistudio2api` → 重启
- **重启服务**：菜单 → 重启服务（也支持 停止服务）
- **局域网访问**：菜单 → 局域网访问 → `LISTEN_ADDR=0.0.0.0:2048`，同时开启管理端登录
  （`ADMIN_AUTH_ENABLED=true`）并生成管理端密码；对话框里会显示 `admin` + 该密码，重新打开
  菜单也能看到。上游控制台默认只接受本机请求，其他设备必须先用这套账号登录，否则会返回
  `control_plane_forbidden`（关闭时保持免登录，本机 WebView 打开即可用）
- **显示分辨率**：菜单 → 显示分辨率 → 「跟随手机（竖屏，默认）」/ 1920×1080 / 1280×1024 /
  1440×900 / 1280×800 / 自定义（`宽x高`，320-4096）。这个值就是 Xvfb `-screen` 的几何尺寸
  （经 `VNC_SCREEN` 传给 `vnc-serve.sh`），登录视图用同一比例做等比适配，所以不会留黑边。
  **Camoufox 的窗口大小不由我们决定**（rootfs 里没有窗口管理器、上游也没传窗口尺寸参数），
  窗口比桌面大就会被 framebuffer 裁掉，因此「调大分辨率」是让浏览器完整可见的手段（整体变小，
  用双指缩放看细节）。改完自动重启服务与显示会话（Xvfb 尺寸只在启动时生效）。
- **后台保活**：菜单 → 后台保活设置 → 申请忽略电池优化（前台服务 + `START_STICKY` + 崩溃重启）
- **桌面浏览器方式打开**：默认即等价于 Chrome 的「桌面版网站」
  - 桌面 Chrome UA（`Windows NT 10.0; Win64; x64 ... Chrome/126`）
  - 控制台首页自带 `width=device-width`，手机上会渲染成窄布局、部分面板被裁掉。
    Chrome 的「桌面版网站」是靠忽略这条声明实现的，这里等价地把它改写成
    `width=1280`：document-start 脚本（`WebViewCompat.addDocumentStartJavaScript`）
    在页面脚本前改写 `<meta name="viewport">`，`onPageFinished` 再兜底扫一遍
  - `useWideViewPort` + `loadWithOverviewMode` 会把 1280 宽的桌面布局缩放到屏幕宽度，
    因此**内容完整可见不被遮挡**，再配合双指捏合放大看细节
  - 没有 UA / 缩放开关，打开就是桌面版

## 登录：点网页里的「浏览器登录」即可

管理端账号页的「浏览器登录」按钮对应后端的 `POST /api/accounts`（重新登录是
`POST /api/accounts/<id>/login`）。这两个请求在服务端会启动一个**有界面**的
Camoufox，并由请求本身一直阻塞到用户登录完成。

因此这里不需要额外的登录入口：

1. `ServerService` 启动后端的同时，在 rootfs 内拉起虚拟桌面
   ```
   Xvfb :99  →  x11vnc -localhost :5900  →  websockify 127.0.0.1:6080  →  WebView /login.html
   ```
   后端进程带 `DISPLAY=:99`，它打开的 Camoufox 就画在 `:99` 上。
   虚拟桌面按手机的**竖屏比例**开屏（短边 1080px），这样 noVNC「缩放到适应」时
   几乎不浪费屏幕；短边取 1080 是为了让自行决定窗口大小的 Camoufox 不会被桌面裁掉。
2. `MainActivity` 注入一段 JS 钩住 `fetch`/`XHR`：一旦发现上述请求发出，就自动
   把界面切换到登录窗口（全屏覆盖的 noVNC 视图），用户直接在屏幕上完成
   Google 登录（短信验证码 / Passkey / 人机验证都行）。
3. 服务端登录完成、请求返回后，自动切回控制台并刷新账号列表。

### 登录窗口的显示与缩放

- `#screen` 只做容器，画布尺寸由 noVNC 内联设置；**不要用 `!important` 覆盖 `canvas`
  的宽高**，否则会把远端桌面拉伸/裁切
- `PinchZoomLayout` 把 WebView 摆成与虚拟桌面同比例的最大矩形（黑边即容器背景），
  noVNC 的 autoscale 正好铺满，因此默认就是「整屏适配」
- noVNC 对每个触摸事件都 `preventDefault`，WebView 自带的捏合永远触发不了；
  因此双指手势在 `PinchZoomLayout` 里处理，并且**不属于** WebView，单指仍按鼠标
  事件传给 noVNC（触摸坐标由 Android 反向映射，noVNC 的落点依然准确）
- 缩放是通过**改 WebView 的布局尺寸**实现的（不是视图变换）：WebView 重新排版后
  noVNC 的 `ResizeObserver` 会按新容器重算缩放，Chromium 按最终尺寸重新栅格化，
  所以放大后文字依然清晰；缩放范围 1x–3x，只允许覆盖式平移
- 顶部「适配」按钮复位到整屏适配状态

登录窗口顶部有「返回界面」和「适配」按钮；中途退出不会中断登录，完成后仍会自动刷新。
`.env` 里 `REQUEST_TIMEOUT=10m`，这个值同时是登录的等待上限（上游默认 5m）。

## 说明 / 限制

- Rootfs 约 733 MB（APK 约 737 MB），首次启动解压需约 3 分钟、约 2.3 GB 空间
- 后端默认 `AUTO_START=false`：启动 App 只起**管理端**，生成服务需在网页里点「启动服务」
- 移动端保守参数：`WARM_WORKER_LIMIT=1`、`MAX_ACTIVE_WORKERS=2`、`WARM_STARTUP_CONCURRENCY=1`、`PER_ACCOUNT_CONCURRENCY=1`；改 `.env` 后重启服务生效
- 内置 Camoufox 通过 `CAMOUFOX_PATH=/opt/camoufox/camoufox-bin` 复用，不会再去下载
- 虚拟桌面随服务一起启停，空闲时几乎无占用；只监听回环（x11vnc `-localhost`），noVNC 也只绑定 `127.0.0.1:6080`
- 虚拟桌面里没有窗口管理器，Camoufox 窗口大小由它自己决定：桌面开得够大就不会被裁，
  但窗口未必铺满整个桌面，可能出现黑边 —— 用双指缩放放大即可
- 上游为 MIT 许可；本模块仅做 Android 打包与平台适配，非官方发布
