## 修复：浏览器登录有时不显示登录窗口，只看到横屏的控制台

上游 AIStudio2API **v0.3.13** 把账户登录改成了异步任务：`POST /api/accounts` 不再阻塞到登录结束，而是立刻返回

```
HTTP 202 {"task":{"id":"…","kind":"login","state":"running",…}}
```

启动器注入的登录钩子仍把“请求 resolve”当作“登录完成”，于是 0.1 秒后就收起登录层；而转横屏是异步的，登录层还没显示出来就被关掉，最终停在横屏的控制台页面。是否复现取决于旋转与 202 返回的先后，所以表现为**偶发**。

现在钩子识别 `202 + task.id`，改为每 2 秒轮询 `GET /api/accounts/tasks`（最长 10 分钟），任务进入 `done` / 失败状态才回调结束；期间登录请求保持 pending，旋转未完成时会持续重投，登录窗口一定能显示。旧后端（同步返回）保持原行为。

## 同时修复

- 部分厂商 ROM 的 GNU tar 解压 rootfs 时因 `chown 0:0` 被拒返回 1 而误报「启动失败 tar=1」：现在 `chown`/`chmod`/`utime`/链接的 `Operation not permitted`、`Permission denied` 视为无害，仍用 `bin/bash` 与后端二进制是否存在做完整性校验

## 体积与组件

- APK 不含内置浏览器（172 MB），浏览器由后端下载到 `runtime/camoufox`，升级 APK 时完整保留
- 内置后端 **AIStudio2API v0.3.13**，浏览器版本 `158.0.1-clock.2`
- 启动时清理 v0.3.13 已弃用的 `runtime/geoip`

## 设备要求

| 项 | 要求 |
|---|---|
| 系统 | Android 8.0（API 26）及以上 |
| CPU | arm64-v8a |
| 存储 | ≥ 8 GB 空闲（APK 172 MB + rootfs ~730 MB + 浏览器 1.3 GB） |
| 内存 | 建议 ≥ 6 GB |
| Root | 不需要 |

## 版本

- App **0.2.24**（versionCode 26）
- 与 0.2.8 起同一证书，可覆盖安装，账号与已下载的浏览器不受影响

## 已知环境问题（非本版引入）

- 未开代理时后端导航 AI Studio 会报 `NS_ERROR_NET_TIMEOUT`
- 连续重复触发登录会出现两个浏览器抢同一 profile，报 `signal: killed` 与 Juggler 重复加载，等一次任务结束再点即可

- 大小 180,266,554 B
- SHA-256 `72fcbd069e39acf9e710209bfb4e259d4c97d798627bde93d59ee0c0599dc5d2`
