#!/usr/bin/env bash
#
# Downloads the prebuilt Ubuntu 24.04 rootfs (rootfs.tar.zst) into
# app/src/main/assets/ so the project can be built from source.
#
# The rootfs is ~823MB and exceeds GitHub's 100MB per-file limit, so it is NOT
# committed to the repository. It is published as a GitHub Release asset.
#
# Usage:
#   ./scripts/fetch-rootfs.sh <url-or-path>
#   ROOTFS_URL=<url> ./scripts/fetch-rootfs.sh
#
# Examples:
#   ./scripts/fetch-rootfs.sh https://github.com/<you>/<repo>/releases/download/v1.0/rootfs.tar.zst
#   ./scripts/fetch-rootfs.sh /path/to/local/rootfs.tar.zst
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
DEST="$PROJECT_ROOT/app/src/main/assets/rootfs.tar.zst"

SRC="${1:-${ROOTFS_URL:-}}"

if [ -z "$SRC" ]; then
    echo "错误: 未提供 rootfs 来源。" >&2
    echo "用法: $0 <url-or-path>   或设置环境变量 ROOTFS_URL" >&2
    echo >&2
    echo "rootfs.tar.zst 作为 GitHub Release 资源发布，请从 Release 页面获取下载链接。" >&2
    exit 1
fi

if [ -f "$DEST" ]; then
    echo "已存在: $DEST"
    echo "如需重新下载，请先删除该文件。"
    exit 0
fi

echo "下载 rootfs 到: $DEST"
mkdir -p "$(dirname "$DEST")"

if [ -f "$SRC" ]; then
    # Local file
    cp "$SRC" "$DEST"
elif [[ "$SRC" == http://* || "$SRC" == https://* ]]; then
    if command -v curl >/dev/null 2>&1; then
        curl -L --fail --progress-bar -o "$DEST" "$SRC"
    elif command -v wget >/dev/null 2>&1; then
        wget -O "$DEST" "$SRC"
    else
        echo "错误: 需要 curl 或 wget 来下载。" >&2
        exit 1
    fi
else
    echo "错误: 无法识别的来源: $SRC" >&2
    exit 1
fi

echo "完成: $DEST"
echo "现在可以运行 ./gradlew :app:assembleDebug 构建 APK。"
