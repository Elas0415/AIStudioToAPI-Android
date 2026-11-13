## 图形显示整体重做（对齐 AIStudioToAPI）

登录窗口不再使用 TigerVNC（Xvnc），改成上游 AIStudioToAPI 同款的「无头造屏 + 取帧 + 转协议 + 网页显示」链路：

```
Xvfb :99  ->  x11vnc 127.0.0.1:5901  ->  websockify 127.0.0.1:6080  ->  node 网关 127.0.0.1:6081  ->  noVNC（App 内 WebView）
```

- 新增 `display-serve.sh`（造屏/取帧/转协议/网关的启动与就绪探测）与 `server.js`（静态托管 noVNC，并把 `/vnc` 的 WebSocket 握手转发给 websockify）
- 删除 `assets/xvnc`、`vnc-serve.sh` 及其部署逻辑
- 修复「转横屏后登录流程立即结束」：
  - Xvfb 在应用私有目录创建 `/tmp/.X99-lock` 被 SELinux 拒绝（`link() failed: Permission denied`），加 `-nolock -ac`
  - x11vnc 在 PRoot 下 MIT-SHM 等扩展不可用会直接退出，加 `-noshm -noxdamage -noxrecord -noxfixes -noxinerama -noscr -nowf`
- 各环节日志：`/tmp/xvfb.log`、`x11vnc.log`、`websockify.log`、`display-server.log`、`tinywm.log`

## 浏览器与后端

- 内置 **Camoufox 158.0.1-beta.41**（AIStudio2API 0.3.7 要求的版本），后端内置 **v0.3.7**
- 不再固定 `CAMOUFOX_PATH`：后端自管 `runtime/camoufox`，校验 `application.ini` 版本，不符时控制台「重试」会真正下载并生效，上游再升级浏览器也能自动跟进
- 内置浏览器以软链接接入该路径，首次启动无需下载 1.3GB；若使用不带浏览器的构建，后端会自行下载

## 其它（0.2.16–0.2.20 累计）

- 后端启动失败时直接在界面显示原因 + 日志尾部，不再干等 5 分钟超时
- 菜单新增「运行环境诊断」（机型/ABI/内核/页大小/SELinux/空间/PRoot 冒烟测试/日志尾部）
- 控制台修改 API 密钥不再被启动器覆盖；应用内更新完成后自动重启服务

## 版本组成

| 组件 | 版本 |
|---|---|
| App | 0.2.20（versionCode 22） |
| 后端 | AIStudio2API v0.3.7（官方原版） |
| Camoufox | 158.0.1-beta.41（内置） |
| 运行环境 | PRoot + Ubuntu 24.04 arm64 |

## 安装

`adb install -r appgo-debug.2.0.20.apk`（arm64，debug 签名，与 0.2.8 起同一证书，可覆盖安装）

- 大小：1,268,188,820 B
- SHA-256：`314a575392debc746d592c11ea48155ee59f094e3367c617410506a39d804431`
- 从旧版升级会重新解压一次 rootfs（约 3–5 分钟），`auth/`、`.env`、`runtime/` 保留，不用重新登录
