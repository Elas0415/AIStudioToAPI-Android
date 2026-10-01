# NOTICE / 归属与许可说明

本仓库 **AIStudioToAPI-Android** 是以下项目的**非官方 Android 移植分支（unofficial
fork / port）**，并非原作者发布：

- **上游项目**：[iBUHub/AIStudioToAPI](https://github.com/iBUHub/AIStudioToAPI)
  - 作者：iBUHub 及贡献者
- **上游的上级来源**：[Ellinav/ais2api](https://github.com/Ellinav/ais2api)
  - 作者：Ellinav

上游项目及其衍生部分均采用 **Creative Commons Attribution-NonCommercial 4.0
International（CC BY-NC 4.0）** 许可证。本移植分支**完全沿用**该许可证，完整条款见
[`LICENSE`](./LICENSE)。

## 本分支做了什么（Changes）

相对于上游，本仓库新增/修改的部分主要包括：

1. 将 AIStudioToAPI 后端、Web 控制台与 Node.js 24 打包为 Android 应用；
2. 内置 PRoot + Ubuntu 24.04 glibc rootfs（用于运行 glibc 版 Camoufox）；
3. 实现 App 内自动登录（调用上游 `scripts/auth/saveAuth.js`）、内置 noVNC 的
   VNC 手动登录、认证文件导入、命令控制台等原生入口；
4. 针对 Android/SELinux 的兼容性修补（私有 `/dev`、`/etc/hosts`、Xvfb/x11vnc
   wrapper 等）；
5. 对 `rootfs.tar.zst` 不随仓库分发，改为通过 GitHub Release 资源提供。

> 注意：本分支未修改上游 Node/前端业务逻辑本身，仅做打包与平台适配（部分
> `scripts/auth/saveAuth.js` 的等待参数在运行时由本 App 覆盖）。

## NonCommercial（非商业）声明

依据 CC BY-NC 4.0，**不得将本项目或其衍生作品用于商业目的**，包括但不限于：
付费分发、内置广告、以本项目作为付费服务的一部分，或任何以获取商业利益为主要
目的的使用。

## 第三方组件

- **Camoufox**（Firefox 分支）：见 [daijro/camoufox](https://github.com/daijro/camoufox)
  的许可证（MPL 2.0 等）。
- **noVNC**：MPL 2.0，见 `app/src/main/assets/novnc/LICENSE.txt`。
- **pako**：MIT，见 `app/src/main/assets/novnc/vendor/pako/LICENSE`。
- **PRoot**：GPLv2+（`app/src/main/jniLibs/arm64-v8a/libproot.so`）。
- **Node.js**、**Ubuntu 24.04** 及其包：各自许可证。

## 免责声明

本项目仅供学习与研究使用。使用者需自行遵守 Google 服务条款及所在地法律。
作者不对任何账号封禁、数据丢失或其他后果负责。
