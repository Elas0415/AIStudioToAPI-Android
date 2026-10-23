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
    opt/camoufox/                             Camoufox 152.0.4-beta.29（来自基础 rootfs）
    opt/novnc/                                虚拟桌面前端（noVNC）
```

## 关键文件

- `RuntimeEnv.java`：解压 rootfs、写 `.env`、拼 proot 命令（含 `DISPLAY=:99`）
- `ServerService.java`：前台服务跑 `./aistudio2api --open-ui=false`，同时拉起虚拟桌面，崩溃自动重启
- `MainActivity.java`：手机版网页 + 「浏览器登录」自动切到虚拟桌面（登录窗口的应用栏菜单）
- `PinchZoomLayout.java`：登录窗口的双指缩放容器（noVNC 会吞掉 WebView 自带的捏合）
- `UpdateChecker.java`：检查上游 release 并覆盖二进制
- `assets/scripts/start-aistudio2api.sh` / `vnc-serve.sh`
- `assets/xvnc/Xtigervnc`：TigerVNC 1.13.1 arm64（X server + RFB），启动时部署为 `/usr/bin/Xvnc`
- `src/main/native/tinywm.c`：自建的极简窗口管理器（`scripts/build-tinywm.sh` 交叉编译成
  `assets/scripts/tinywm`），把 Camoufox 的窗口铺满虚拟桌面
- `assets/novnc/login.html`：自动连接、1:1 铺满、画面出现后自动撤掉启动提示的 noVNC 页面
- `res/values/dimens.xml`：`login_bar_height`，应用栏高度（虚拟桌面高度就是屏幕高减它）

## 功能

- **刷新页面**：菜单 → 刷新页面
- **检查原项目更新**：菜单 → 检查原项目更新 → 调用本地后端的
  `GET /api/update?refresh=1`，与网页控制台使用**同一套**上游更新逻辑：release 缓存、
  代理、semver、SHA-256 摘要校验、按平台选择发行包、`binary` 模式原地替换并重启。
  App 不再自己下载/解压 GitHub 包；需先在网页里启动本地服务。
- **共享目录**：菜单 → 共享目录，显示手机路径（`.../files/shared`）和容器内路径
  `/mnt/host`。把文件放进手机路径后，登录浏览器、容器内终端和 Web 控制台都能通过
  `/mnt/host` 访问；该目录使用 App 外部文件目录，不需要存储权限。
- **重启服务**：菜单 → 重启服务。停止当前后端并重新执行 `./aistudio2api --open-ui=false`，
  同时重建虚拟桌面（Xvnc 几何只在启动时生效），用来重新加载管理页保存到 `.env` 的设置；
  也支持 停止服务
- **显示跟随手机**：虚拟桌面固定为手机横屏的实际像素（长边 × (短边 − 应用栏)，见
  `RuntimeEnv.virtualScreenSize`），登录视图与远端 framebuffer 1:1，noVNC 不需要缩放、
  左右也没有黑边。显示栈随服务重启一起重建。
  - **登录窗口是横屏的**：点「浏览器登录」时 `MainActivity` 等 Activity 真正转到
    `SCREEN_ORIENTATION_LANDSCAPE` 后再显示 noVNC 视图，避免先按竖屏宽度测量导致
    「浏览器只占左上角一小块」。manifest 里的 `configChanges="orientation|screenSize"`
    让旋转不重建 Activity，两个 WebView 都活着；`login.html` 监听
    `resize`/`orientationchange`/`ResizeObserver`，视图尺寸一变就重新执行 noVNC 自己的适配。
  - **自动 1:1 铺满**：`PinchZoomLayout` 按远端宽高比把 WebView 放到视图里，noVNC 的
    `scaleViewport` 再按 WebView 尺寸适配；连接成功时会把残余的捏合缩放复位到 1.0，
    所以第一次打开就是整屏，不需要手动缩放。想放大再双指捏合即可。
  **Camoufox 的窗口大小不由我们决定**（上游没传窗口尺寸参数），所以 rootfs 里跑一个自建的
  极简窗口管理器 `tinywm`（见下）把每个顶层窗口铺满桌面：浏览器完整可见，不再被 framebuffer 裁掉。
- **登录窗口铺满桌面（tinywm）**：`src/main/native/tinywm.c` 只依赖 `libX11`，启动时
  接管 SubstructureRedirect，把每个「应用窗口」改成整屏（0,0,宽,高）并保持不被缩回去，
  同时支持点击聚焦，保证键盘输入仍到达浏览器；override-redirect 窗口（下拉菜单、气泡）
  和小于 100×100 的辅助窗口不动。若已有窗口管理器在跑则直接退出，不会破坏显示。
  编译：`scripts/build-tinywm.sh`（用 `aarch64-linux-gnu-gcc` 链接 rootfs 里的
  `libX11.so.6`），产物 `assets/scripts/tinywm` 每次启动由 `RuntimeEnv` 部署到 rootfs 的
  `/usr/local/bin`。
  - **为什么必须「窗口长大了再接管」而不是只在 map 时判断**：Firefox/GTK 不会按最终尺寸
    map 主窗口，而是先 map 一个 10×10 的占位窗口、之后再 resize 成真实尺寸。用这个 rootfs
    里的 Camoufox（152.0.4-beta.29）在真实 X 服务器上实测：
    `0x200001 "Firefox": ("camoufox-default" "Camoufox-default") 10x10+10+10`。
    只看 map 时刻的尺寸（旧实现是 `<200×200 就跳过`）会永远不接管浏览器窗口，随后那条
    `ConfigureRequest` 又被当作「非托管窗口」原样放行 —— 于是窗口保持自己的尺寸（例如
    1280×720 或 1920×1080），在 1080 宽的桌面上右侧 200~840 px 直接画到 framebuffer 之外，
    那部分像素从未进入 Xvnc 的 framebuffer，noVNC 忠实显示的就是「被裁掉右边的浏览器」。这也是「远端桌面尺寸
    明明等于手机屏幕、画面却还是缺一块」的真正原因（**裁剪发生在远端，不在页面**）。
    现在接管分三条路：map 时就够大的窗口、`ConfigureRequest` 把未托管窗口放大到应用尺寸时、
    以及每 1 秒的看门狗（重扫 root 子窗口 + 复核已接管窗口的几何）。实测（Xvnc 2400×912）：
    占位窗口放大到 1280×720 → 立刻变成 2400×912 铺满；已存在的 1920×1080 窗口被看门狗
    接管；150×90 / 90×150 的小窗口保持不动。
  - 日志在 `/tmp/tinywm.log`（`TINYWM_VERBOSE=1` 时连看门狗的每次纠正也记）；
    `vnc-serve.sh` 启动后会检查进程并在日志里写明是否生效。
- **后台保活**：菜单 → 后台保活设置 → 申请忽略电池优化（前台服务 + `START_STICKY` + 崩溃重启）
- **手机浏览器方式打开**：默认按手机版网站加载
  - 使用 WebView 自带的移动端 UA，不再伪装桌面 Chrome
  - 不覆盖 `<meta name="viewport">`，控制台自己的 `width=device-width` 生效，
    渲染成适合手机宽度的布局
  - `useWideViewPort` / `loadWithOverviewMode` 关闭，页面按 1:1 的 CSS 像素排版
  - 仍保留双指捏合放大，方便查看细节

## 登录：点网页里的「浏览器登录」即可

管理端账号页的「浏览器登录」按钮对应后端的 `POST /api/accounts`（重新登录是
`POST /api/accounts/<id>/login`）。这两个请求在服务端会启动一个**有界面**的
Camoufox，并由请求本身一直阻塞到用户登录完成。

因此这里不需要额外的登录入口：

1. `ServerService` 启动后端的同时，在 rootfs 内拉起虚拟桌面
   ```
   Xvnc :99（X 服务器 + RFB 服务器，-localhost :5900）  →  tinywm  →  websockify 127.0.0.1:6080  →  WebView /login.html
   ```
   `Xvnc` 是 TigerVNC 的 `Xtigervnc`（arm64，随 APK 以 `assets/xvnc` 提供，
   `RuntimeEnv.deployDisplayStack` 每次启动部署到 `/usr/bin/Xvnc`）。
   相比原来的 `Xvfb + x11vnc` 两进程方案：只有一个进程、不需要 x11vnc 轮询/比对整个
   framebuffer，刷新由 X 服务器内部的 damage 事件驱动，几何是真正的 X screen 尺寸
   （RANDR），因此登录页滚动、输入时更省 CPU，也不会出现「x11vnc 还没扫到那一帧」。
   后端进程带 `DISPLAY=:99`，它打开的 Camoufox 就画在 `:99` 上。
   虚拟桌面默认按**登录视图自身的像素尺寸**开屏（屏幕真实像素宽 × (高 − 应用栏高度)），
   并由 `tinywm` 把 Camoufox 的窗口铺满整屏，所以 noVNC「缩放到适应」就是 1:1 铺满。
2. `MainActivity` 注入一段 JS 钩住 `fetch`/`XHR`：一旦发现上述请求发出，就自动
   把界面切换到登录窗口（全屏覆盖的 noVNC 视图），用户直接在屏幕上完成
   Google 登录（短信验证码 / Passkey / 人机验证都行）。
3. 服务端登录完成、请求返回后，自动切回控制台并刷新账号列表。

### 登录窗口的界面与缩放

登录窗口只有两段：**自己的应用栏 + 整屏的浏览器**，没有任何自绘按钮行占地方
（对照原项目 `:app` 的 VNC 登录效果）：

- 应用栏标题是 `AIStudio2API`，副标题显示连接状态（连接上并出现画面后自动清空），
  溢出菜单里是全部操作：**适配（回到整屏）**、**显示键盘**、**输入文本…**、**适配信息**、
  **重连**、**返回界面**
- **重连不会再用手拼的 Google 登录 URL**：`accounts.google.com/v3/signin/identifier` 缺少
  后端生成的 `dsh` 等参数，直接访问会返回 HTTP 400。重连改为把远端浏览器送到
  `https://aistudio.google.com/`，由 AI Studio 自己跳转到有效的登录页
- **打开即铺满，不需要手动缩放**：虚拟桌面固定为手机横屏的实际像素（例如 2400×912），
  `PinchZoomLayout` 按远端宽高比放置 WebView，noVNC 再按 WebView 尺寸 `scaleViewport`；
  连接成功时还会把上一次残留的捏合缩放复位到 1.0。若仍看到黑边/只占一块，先看
  「适配信息」里的 fb/view/canvas 数值
- **适配信息**：把页面实测的几何报出来（远程桌面 framebuffer、视图、canvas、缩放比例、
  是否溢出），可直接判断「是页面没适配」还是「浏览器窗口画到了远端桌面之外」：
  画布尺寸 = 视图尺寸就说明页面显示的已经是完整一帧画面；此时若仍被裁，问题在远端
  （`tinywm`/窗口），因为缺的那些像素从来没有进入 Xvnc 的 framebuffer。数值同时写进 logcat
  （`novnc metrics {...}`）
- **页面只做「重新适配」，不自己做缩放**：`login.html` 在连接成功、视图尺寸变化
  （软键盘弹出/收起、旋转、覆盖层再次显示）时重新断言 `rfb.scaleViewport = true`，
  也就是重跑 noVNC 自己的缩放；它不会叠加第二层固定比例或 `transform`，否则就是
  「把已经很小的画面再缩一次」并且让鼠标/触摸坐标失真
- 「输入文本…」是原生输入框（不再用 `window.prompt`，WebView 默认不弹 prompt），
  逐字符按 18ms 间隔发给远端浏览器，换行当回车 —— 验证码、密码、搜索词都能直接输
- 应用栏高度就是 `@dimen/login_bar_height`（56dp），而 `RuntimeEnv` 正是用它来算虚拟桌面
  高度，所以视图与远端 framebuffer 尺寸严格一致（1:1 铺满）
- **登录窗口是横屏的**：打开时 `setRequestedOrientation(LANDSCAPE)`，关闭时回到
  `UNSPECIFIED`；旋转后视图尺寸变化，页面按上面那条规则重新适配。因此
  `login.html` 必须是最新的那份（带 `resize`/`orientationchange`/`ResizeObserver`）——
  `RuntimeEnv.NOVNC_MARKER` 就是干这个的：改 `assets/novnc` 下任何文件都要同时改它，
  否则 `deployNovnc()` 会因为 marker 已存在而**直接跳过复制**，设备上继续跑旧页面
  （旧页面完全没有 resize 处理，旋转后会留着旧的缩放比例）
- **软键盘**：远端桌面是 canvas，Android 永远不会为它弹键盘，所以页面里放了一个透明的
  textarea 当输入目标：**点一下桌面**（单指轻点，捏合不算）就会聚焦它、系统键盘随之弹出，
  输入的内容（含中文/表情，按 Unicode keysym）逐字符发给远端，退格/回车/Tab 也照发；
  textarea 自身 `pointer-events: none`，所以点击、双指缩放完全不受影响。应用栏的
  「显示键盘」是手动弹出键盘的入口（`window.__a2api.keyboard()`）
- noVNC 页面自己不再画状态条/按钮，只在画面还是黑的时候显示一个「正在启动登录浏览器…」
  提示：`login.html` 每 400ms 用 `drawImage` 采样远端画布，一旦出现非黑像素立刻撤掉
  （本来就无事可做的等待不再像“卡住的占位文字”）
- `#screen` 只做容器，画布尺寸由 noVNC 内联设置；**不要用 `!important` 覆盖 `canvas`
  的宽高**，否则会把远端桌面拉伸/裁切
- noVNC 对每个触摸事件都 `preventDefault`，WebView 自带的捏合永远触发不了；
  因此双指手势在 `PinchZoomLayout` 里处理，并且**不属于** WebView，单指仍按鼠标
  事件传给 noVNC（触摸坐标由 Android 反向映射，noVNC 的落点依然准确）
- 缩放是通过**改 WebView 的布局尺寸**实现的（不是视图变换）：WebView 重新排版后
  noVNC 的 `ResizeObserver` 会按新容器重算缩放，Chromium 按最终尺寸重新栅格化，
  所以放大后文字依然清晰；缩放范围 1x–3x，只允许覆盖式平移
- 页面 `desktopsize` 事件会把真实 framebuffer 尺寸回报给 App（`AndroidVnc.onRemoteSize`），
  `PinchZoomLayout` 用它做等比适配：即使显示会话还是旧几何（比如旋转屏幕后没重启服务），
  也不会因为比例猜错而多出黑边

登录窗口全程沉浸式（无状态栏/导航栏），中途「返回界面」不会中断登录，完成后仍会自动刷新。
`.env` 里 `REQUEST_TIMEOUT=10m`，这个值同时是登录的等待上限（上游默认 5m）。

## 说明 / 限制

- Rootfs 约 690 MB（APK 约 696 MB），首次启动解压需约 3 分钟、约 2.0 GB 空间
- 后端默认 `AUTO_START=false`：启动 App 只起**管理端**，生成服务需在网页里点「启动服务」
- `LISTEN_ADDR`、`ADMIN_AUTH_*` 由管理页「服务配置」写入 `.env`，启动器只保留、不覆盖；
  改端口/监听地址/管理端登录后点 App 菜单「重启服务」即可。App 不再提供「局域网访问」菜单
- 移动端保守参数：`WARM_WORKER_LIMIT=1`、`MAX_ACTIVE_WORKERS=2`、`WARM_STARTUP_CONCURRENCY=1`、`PER_ACCOUNT_CONCURRENCY=1`；改 `.env` 后重启服务生效
- 内置 Camoufox 通过 `CAMOUFOX_PATH=/opt/camoufox/camoufox-bin` 复用，不会再去下载
- **登录浏览器的安全沙箱提示**：PRoot 无法提供 Firefox 内容进程所需的命名空间，必须设置
  `MOZ_DISABLE_CONTENT_SANDBOX=1`；Firefox 146+ 因此会显示
  “The security sandbox is disabled…” 通知。启动器给 `camoufox-bin` 加了一层包装器，
  在登录 profile 里注入 `chrome/userChrome.css` 和
  `toolkit.legacyUserProfileCustomizations.stylesheets=true`，隐藏这个通知栏。
  沙箱本身仍因 PRoot 限制而关闭，这是运行环境的预期行为，不是登录失败。
- **Camoufox 版本与上游对齐**：rootfs 内置 `152.0.4-beta.29`（AIStudio2API v0.2.13 的指纹/TLS
  传输基准），`WAA_BACKEND` 默认 `camoufox`。之前打包的 `135.0.1-beta.24` 会被 AI Studio 判为
  「未配置的浏览器」，`GenerateContent` / `ProxyUnaryCall` 返回 HTTP 403、协议错误码 7
  （`The caller does not have permission`）。如需不启动浏览器的纯 Go WAA，可在 `.env` 里设置
  `WAA_BACKEND=go`，两条路径都可用。
- rootfs 升级（`ROOT_MARKER` 变化）时会保留 `/opt/aistudio2api/auth`、`.env` 与 `runtime/`，
  账号登录态、控制台设置和请求记录不会因重新解压而丢失。
- 虚拟桌面随服务一起启停，空闲时几乎无占用；Xvnc 只监听回环（`-localhost`，且 `-SecurityTypes None`
  只在回环上有意义），noVNC 也只绑定 `127.0.0.1:6080`
- 虚拟桌面里跑一个自建的极简窗口管理器 `tinywm`，把 Camoufox 的窗口铺满整屏（不再依赖
  「桌面开得够大」）；万一个窗口管理器失败，显示会话照常工作，只是窗口可能被裁
- 上游为 MIT 许可；本模块仅做 Android 打包与平台适配，非官方发布
