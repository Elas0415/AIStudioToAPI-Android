# AIStudioToAPI-Android v1.0

非官方 Android 移植分支。把 [iBUHub/AIStudioToAPI](https://github.com/iBUHub/AIStudioToAPI)
后端、Web 控制台与 PRoot + Ubuntu 24.04 glibc 运行环境（Node.js 24 + Camoufox arm64）
打包为单一 Android 应用。

> ⚠️ 许可证：CC BY-NC 4.0（署名—非商业性使用），**禁止商业用途**。详见 `LICENSE` / `NOTICE.md`。

## 下载

| 文件 | 说明 |
|---|---|
| `AIStudioToAPI-Android-v1.0-debug.apk` | 可直接安装的 APK（约 828MB，arm64-v8a，Android 8.0+） |
| `rootfs.tar.zst` | Ubuntu 24.04 arm64 运行环境（约 823MB）。**从源码构建时**才需要，普通用户无需下载 |

已安装 APK 的用户**不需要**单独下载 `rootfs.tar.zst`（已内置于 APK）。

## 校验（SHA-256）

```
a4039c2e9969bc3e3c1e1c77fa5949d3dfebb1c608f3b946ba78e8a0d0400e68  rootfs.tar.zst
e59aa7e189dbf4aaa14340e7ce949410a2bb9da9d520fbe3d2b5c64171083fbe  AIStudioToAPI-Android-v1.0-debug.apk
```

## 安装

1. 下载 APK 并侧载（手机需允许「安装未知来源应用」）。
2. 打开 App，首次启动会解压运行环境（约数分钟）。
3. 菜单 →「添加账号（VNC 手动登录）」或「添加账号（自动登录）」完成 Google 登录。
4. 客户端使用 `http://127.0.0.1:7860/v1`，API Key 默认 `123456`（可在菜单 →「自定义 API Key」修改）。

## 从源码构建

```bash
./scripts/fetch-rootfs.sh <rootfs.tar.zst 的下载链接>
export ANDROID_HOME=<sdk>
./gradlew :app:assembleDebug
```

## 注意

- 仅支持 arm64-v8a；内存建议 8GB 起。
- 本项目为非官方分支，请优先支持上游 [iBUHub/AIStudioToAPI](https://github.com/iBUHub/AIStudioToAPI)。
- Google 风控/人机验证可能导致自动登录失败，此时请改用 VNC 手动登录或导入认证文件。
