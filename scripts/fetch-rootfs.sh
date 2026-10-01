#!/usr/bin/env bash
#
# Downloads the prebuilt Ubuntu 24.04 rootfs (rootfs.tar.zst) into
# app/src/main/assets/ so the project can be built from source.
#
# The rootfs is ~823MB and exceeds GitHub's 100MB per-file limit, so it is NOT
# committed to the repository. It is published as a GitHub Release asset.
#
# Usage:
#   ./scripts/fetch-rootfs.sh                 # 从默认 Release 下载
#   ./scripts/fetch-rootfs.sh <url-or-path>   # 指定链接或本地文件
#   ROOTFS_URL=<url> ./scripts/fetch-rootfs.sh
#
# Examples:
#   ./scripts/fetch-rootfs.sh
#   ./scripts/fetch-rootfs.sh https://github.com/Elas0415/AIStudioToAPI-Android/releases/download/v1.0/rootfs.tar.zst
#   ./scripts/fetch-rootfs.sh /path/to/local/rootfs.tar.zst
#
# 提示：若本机使用 HTTP 代理（如 Clash 7890），大文件下载可能被截断，
# 脚本会自动尝试绕过代理重试。
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
DEST="$PROJECT_ROOT/app/src/main/assets/rootfs.tar.zst"

DEFAULT_URL="https://github.com/Elas0415/AIStudioToAPI-Android/releases/download/v1.0/rootfs.tar.zst"

SRC="${1:-${ROOTFS_URL:-$DEFAULT_URL}}"

if [ -f "$DEST" ]; then
    echo "已存在: $DEST"
    echo "如需重新下载，请先删除该文件。"
    exit 0
fi

echo "获取 rootfs 到: $DEST"
mkdir -p "$(dirname "$DEST")"

download() {
    local url="$1"
    if command -v curl >/dev/null 2>&1; then
        # 优先使用系统代理设置（下载通常可走代理），失败再绕过代理重试。
        curl -L --fail --progress-bar --http1.1 -o "$DEST" "$url" && return 0
        echo "常规下载失败，尝试绕过代理重试…" >&2
        env -u HTTPS_PROXY -u https_proxy -u HTTP_PROXY -u http_proxy \
            curl --noproxy '*' -L --fail --progress-bar --http1.1 -o "$DEST" "$url"
    elif command -v wget >/dev/null 2>&1; then
        wget -O "$DEST" "$url"
    else
        echo "错误: 需要 curl 或 wget 来下载。" >&2
        return 1
    fi
}

if [ -f "$SRC" ]; then
    cp "$SRC" "$DEST"
elif [[ "$SRC" == http://* || "$SRC" == https://* ]]; then
    download "$SRC"
else
    echo "错误: 无法识别的来源: $SRC" >&2
    exit 1
fi

# Sanity check
if [ ! -f "$DEST" ] || [ "$(stat -c %s "$DEST" 2>/dev/null || echo 0)" -lt 100000000 ]; then
    echo "错误: 下载文件异常（可能被截断），请重试。" >&2
    exit 1
fi

echo "完成: $DEST ($(du -h "$DEST" | cut -f1))"
echo "现在可以运行 ./gradlew :app:assembleDebug 构建 APK。"
