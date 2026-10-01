# AIStudioToAPI-Android

> ⚠️ **这是非官方分支项目（Unofficial Fork / Port）**
>
> 本项目是 [**iBUHub/AIStudioToAPI**](https://github.com/iBUHub/AIStudioToAPI)
> 的 **非官方 Android 移植分支**，并非原作者发布。上游的上级来源为
> [Ellinav/ais2api](https://github.com/Ellinav/ais2api)。
>
> - 上游项目及衍生部分均采用 **CC BY-NC 4.0**（署名—非商业性使用）许可证，
>   本分支**完全沿用**该许可证，**禁止任何商业用途**。详见 [`LICENSE`](./LICENSE)
>   与 [`NOTICE.md`](./NOTICE.md)。
> - 请优先支持上游作者：给 [iBUHub/AIStudioToAPI](https://github.com/iBUHub/AIStudioToAPI)
>   点一个 ⭐。
> - 上游业务代码、Web 控制台、`saveAuth.js` 等版权归原作者所有；本仓库仅做
>   Android 打包与平台适配。

把 [AIStudioToAPI](https://github.com/iBUHub/AIStudioToAPI) 后端、Web 控制台与
**PRoot + Ubuntu 24.04 glibc 运行环境**（含 Node.js 24、Camoufox arm64）
打包成一个 Android APP。打开 APP 即自动启动本机服务
（`127.0.0.1:7860`），并加载 Web 控制台。

> 采用 PRoot + glibc rootfs 的关键原因：AIStudioToAPI 依赖 Playwright 驱动
> **glibc 版 Camoufox（Firefox 分支）**。Android 原生 bionic 环境无法运行该
> 二进制；在 glibc 用户空间中运行 Node 后端后即可原样使用项目代码，无需修改。

## 下载

- **APK**：见本仓库 [Releases](../../releases) 页面（侧载安装，需 arm64 设备）。
- **rootfs.tar.zst**：体积约 823MB，超过 GitHub 单文件 100MB 限制，同样作为
  Release 资源提供。**从源码构建前**需先获取它，见下方「构建」。

## 登录方式

### 方式一：App 内自动登录（推荐，无 VNC）

菜单 →「添加账号（自动登录）」，填写：

| 字段 | 说明 |
|---|---|
| Google 邮箱 | 必填 |
| 密码 | 必填 |
| TOTP 密钥 | 可选，Base32（Google Authenticator/Aegis 导出） |
| 恢复邮箱 | 可选 |

App 通过 proot 运行项目自带的 `scripts/auth/saveAuth.js
--non-interactive --headless`，自动完成填邮箱→填密码→2FA→恢复邮箱→
首次协议→检测登录→生成 `configs/auth/auth-N.json` 全流程，
并实时显示脚本日志进度。成功后提示一键重启服务加载新账号。

- **支持**：邮箱+密码、标准 TOTP、恢复邮箱验证、首次协议弹窗
- **不支持**：短信验证码、Google Prompt、Passkey、人机验证

### 方式二：导入认证文件（兜底）

菜单 →「导入认证文件」或在登录页点击「改为导入认证文件」。
在电脑上运行 `npm run setup-auth`（或从网页控制台「下载 Auth」），
把 `auth-N.json` 传到手机后导入。适合开启强验证的账号。

### 方式三：App 内置 VNC 手动登录（推荐用于强验证账号）

菜单 →「添加账号（VNC 手动登录）」。App 在 WebView 内加载**本地打包的 noVNC**
（不依赖任何外网 CDN），连接后端内置的 VNC 会话：

- 后台由后端拉起 `Xvfb :99` + `x11vnc` + `websockify` 及一个 headful Camoufox，
  登录窗口实时显示在 App 内。
- 用户可手动完成**短信验证码 / Google Prompt / Passkey / 人机验证**等自动登录无法
  处理的步骤，这是对「方式一」的完整补充。
- 登录完成后点上方「保存此账号」，App 调用后端 `/api/vnc/auth` 生成
  `configs/auth/auth-N.json`（账号邮箱自动识别，识别不到时弹窗手填），随后提示重启服务。

### 方式四：导入认证文件（兜底）

菜单 →「导入认证文件」或在登录页点击「改为导入认证文件」。
在电脑上运行 `npm run setup-auth`（或从网页控制台「下载 Auth」），
把 `auth-N.json` 传到手机后导入。适合开启强验证的账号。

## 命令控制台

菜单 →「命令控制台」，用白名单命令驱动本地服务（等价于在 Ubuntu 中输入指令）：

| 命令 | 作用 | 对应 Ubuntu 指令 |
|---|---|---|
| `quick-start` | 启动 / 重启本地服务 | `npm run quick-start`（内部直接 `node main.js`） |
| `setup-auth` | 打开 VNC 登录窗口 | `npm run setup-auth` 的图形化版本 |
| `stop` | 停止本地服务 | `Ctrl-C` |
| `status` | 服务端口 / API Key / 账号数 | — |
| `logs` | 查看最近服务日志 | `tail a2a.log` |
| `help` / `clear` | 帮助 / 清屏 | — |

> 出于安全考虑，控制台仅支持以上白名单命令，不开放任意 shell。

## 结构

```
app/src/main/
├─ assets/
│  ├─ rootfs.tar.zst          # Ubuntu 24.04 arm64 rootfs（含 Node24/a2a/Camoufox）
│  ├─ novnc/                  # 内置 noVNC 客户端（部署到 a2a/ui/dist/novnc）
│  ├─ libtalloc.so.2 / libandroid-shmem.so   # proot 依赖
│  └─ scripts/start-a2a.sh    # rootfs 内服务启动脚本
├─ java/com/aistudio/launcher/
│  ├─ MainActivity.java       # WebView 控制台 + 菜单入口
│  ├─ LoginActivity.java      # 方式一：无头自动登录（saveAuth.js）
│  ├─ VncLoginActivity.java   # 方式三：App 内置 VNC 手动登录（noVNC）
│  ├─ ConsoleActivity.java    # 命令控制台（白名单：quick-start/setup-auth…）
│  ├─ AuthImportHelper.java   # 方式四：导入 auth-N.json
│  ├─ ProotRunner.java        # 通用 proot 命令执行器（流式输出/超时/取消）
│  ├─ ServerService.java      # 前台服务：proot 跑 node main.js，崩溃自动重启
│  └─ RuntimeEnv.java         # rootfs 解压、env 文件、proot 命令拼装
└─ jniLibs/arm64-v8a/
   ├─ libproot.so / libproot_loader.so / libunzstd.so
```

## 工作流程

1. `MainActivity` 启动 `ServerService`（前台服务，带通知）。
2. `ServerService` → `RuntimeEnv.ensureReady()`：首次运行解压 rootfs 到
   `filesDir/runtime/rootfs/`，写 `resolv.conf`（收集系统 DNS）。
3. PRoot 启动后端：
   ```
   PROOT_NO_SECCOMP=1 proot -0 -r rootfs -w /opt/a2a \
     -b /dev -b /proc -b /sys /bin/bash /opt/a2a/start-a2a.sh
   ```
   `start-a2a.sh` 内 `exec node main.js`，加载 `/data/a2a.env`
   （API Key / HOST / PORT 由 App 写入）。
4. `MainActivity` 轮询端口 7860，就绪后 WebView 加载控制台。
5. 登录（方式一）：`LoginActivity` → `ProotRunner` 在同一 rootfs 内跑
   `saveAuth.js --non-interactive --headless`，解析中文/英文日志做进度提示，
   失败时给出原因诊断并引导切换到导入方式。

## 构建

> ⚠️ `app/src/main/assets/rootfs.tar.zst`（约 823MB）**不在仓库中**，需先从
> Release 资源获取。首次构建前执行：

```bash
# 方式 A：从 Release 下载（自动获取最新 rootfs）
./scripts/fetch-rootfs.sh

# 方式 B：指定 Release 链接
./scripts/fetch-rootfs.sh https://github.com/Elas0415/AIStudioToAPI-Android/releases/download/v1.0/rootfs.tar.zst

# 方式 C：使用本地已有的 rootfs 文件
./scripts/fetch-rootfs.sh /path/to/rootfs.tar.zst
```

然后构建：

```bash
export ANDROID_HOME=<sdk>
./gradlew :app:assembleDebug
# 产物: app/build/outputs/apk/debug/app-debug.apk (约 870MB)
```

或在 Android Studio 中打开本目录直接 Run ▶（需 arm64 真机/模拟器）。

> 💡 上传/下载大文件时若使用本地 HTTP 代理（如 Clash 7890 端口），代理可能截断
> 传输。可加 `--noproxy '*'` 绕过代理。

## 发布到 GitHub（维护者备忘）

1. 初始化为 Git 仓库并首次提交（`rootfs.tar.zst` 已被 `.gitignore` 忽略）：

   ```bash
   git init
   git add .
   git commit -m "Initial commit: Android port of AIStudioToAPI"
   git branch -M main
   git remote add origin https://github.com/<owner>/AIStudioToAPI-Android.git
   git push -u origin main
   ```

2. 在 GitHub 上创建 Release（如 `v1.0`），上传以下**资源**（不进入 Git 历史）：
   - `rootfs.tar.zst`（约 823MB，Release 单文件上限 2GB）
   - `app-debug.apk`（构建产物，可选）

3. 更新 README 中的下载链接与 `scripts/fetch-rootfs.sh` 的示例 URL。

> 许可证：因上游为 **CC BY-NC 4.0**，本仓库同样为 CC BY-NC 4.0，
> **禁止商业使用**，并必须在显著位置保留署名（见 `README` 顶部与 `NOTICE.md`）。


## 首次启动

- 第一次启动需解压 rootfs（几分钟，取决于设备）。
- 默认生成随机 API Key：菜单 →「API 地址与密钥」查看。
- 局域网访问默认关闭：菜单 →「局域网访问」开启（监听 0.0.0.0）。
- 建议授予「忽略电池优化」（菜单 →「后台保活设置」）防止服务被杀。

## 限制

- 仅支持 arm64-v8a 设备；内存建议 8GB 起（`MAX_CONTEXTS=1`）。
- APK 体积大（含 rootfs 与 Camoufox），仅适合侧载。
- PRoot 为用户态实现，有性能开销；Google 风控/IP 信誉问题与桌面部署相同。
- 方式一登录本质是浏览器自动化，Google 偶发人机验证会导致失败，
  此时应改用方式二。
