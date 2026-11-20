## 修复：部分机型解压 rootfs 时报「启动失败 tar=1」

部分厂商 ROM 的 `/system/bin/tar` 是 GNU tar，解压时会按归档里的 uid/gid 执行 `chown 0:0`，应用用户没有权限，于是打印

```
tar: chown 0:0 './mnt': Operation not permitted
tar: chown 0:0 './etc': Operation not permitted
```

并返回退出码 1。旧版只容忍 toybox 的硬链接报错，其它 stderr 一律判为解压失败，所以直接弹出「启动失败」。

现在把 `chown` / `chmod` / `utime` / `mtime` / 硬链接与软链接的 `Operation not permitted`、`Permission denied`、`Read-only file system` 都视为无害告警（文件其实已经完整解压），完整性仍由「`bin/bash` 与后端二进制是否存在」兜底，真正的解压失败依旧会报出来。

## 同时包含（0.2.22 的体积优化）

- **不再内置 Camoufox**：APK 从 1.27 GB 降到 **172 MB**；浏览器由后端按需下载（`runtime/camoufox`，1.3 GB），升级 APK 时该目录完整保留，不会重复下载
- 内置后端更新到 **AIStudio2API v0.3.13**
- 启动时自动清理 v0.3.13 已弃用的 `runtime/geoip`（51 MB）
- 构建时剔除 npm/npx/corepack、`usr/share/icons`、`/opt/a2a`、`/opt/novnc` 等无用内容（约 150 MB）
- 后端接管浏览器后自动删除内置副本，避免每次浏览器升级都叠加一份 2.3 GB

## 设备要求

| 项 | 要求 |
|---|---|
| 系统 | Android 8.0（API 26）及以上 |
| CPU | arm64-v8a |
| 存储 | 首次安装建议 ≥ 8 GB 空闲（APK 172 MB + rootfs ~730 MB + 浏览器 1.3 GB + 账号数据） |
| 内存 | 建议 ≥ 6 GB |
| Root | 不需要（PRoot） |

## 版本

- App **0.2.23**（versionCode 25）
- 后端 **v0.3.13**
- 浏览器：由后端下载 `158.0.1-clock.2`
- 大小 180,266,109 B
- SHA-256 `9899e39c259dbfcf650442580d70c0ed75b39a488c2c5f4b0337e983e5eef455`

## 安装

`adb install -r appgo-debug.2.0.23.apk`（与 0.2.8 起同一证书，可覆盖安装；已下载的浏览器与账号不受影响）
