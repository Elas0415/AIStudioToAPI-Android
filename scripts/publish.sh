#!/usr/bin/env bash
#
# 一键发布 AIStudioToAPI-Android 到 GitHub。
#
# 前置条件：
#   1. 已安装 gh 并登录：gh auth login
#   2. 已构建 APK 且准备好 Release 资源：
#        dist/AIStudioToAPI-Android-v1.0-debug.apk
#        dist/rootfs.tar.zst
#
# 用法：
#   ./scripts/publish.sh [repo-name]
#
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

REPO_NAME="${1:-AIStudioToAPI-Android}"
DESC="Unofficial Android port of AIStudioToAPI (PRoot + Ubuntu + Camoufox). CC BY-NC 4.0."
APK="dist/AIStudioToAPI-Android-v1.0-debug.apk"
ROOTFS="dist/rootfs.tar.zst"
NOTES="docs/RELEASE_NOTES_v1.0.md"
TAG="v1.0"

echo "==> 检查 gh 登录状态"
gh auth status >/dev/null 2>&1 || { echo "错误: 请先运行 'gh auth login'"; exit 1; }

echo "==> 检查 Release 资源"
for f in "$APK" "$ROOTFS"; do
    [ -f "$f" ] || { echo "错误: 缺少 $f（先构建并放入 dist/）"; exit 1; }
done

echo "==> 确保本地提交已就绪"
git add -A
git diff --cached --quiet || git commit -q -m "Release $TAG"

echo "==> 创建 GitHub 仓库并推送（若已存在则仅推送）"
if gh repo view "$REPO_NAME" >/dev/null 2>&1; then
    echo "    仓库已存在，跳过创建"
    git remote get-url origin >/dev/null 2>&1 || \
        git remote add origin "$(gh repo view "$REPO_NAME" --json sshUrl -q .sshUrl 2>/dev/null || echo "")"
    git push -u origin main
else
    gh repo create "$REPO_NAME" --public --description "$DESC" \
        --source . --remote origin --push
fi

echo "==> 添加 topics"
gh repo edit "$REPO_NAME" \
    --add-topic aistudio --add-topic android --add-topic gemini \
    --add-topic openai-api --add-topic camoufox --add-topic proot || true

echo "==> 创建 Release $TAG 并上传资源（约 1.7GB，请耐心等待）"
if gh release view "$TAG" >/dev/null 2>&1; then
    gh release upload "$TAG" "$APK" "$ROOTFS" --clobber
else
    gh release create "$TAG" "$APK" "$ROOTFS" \
        --title "AIStudioToAPI-Android $TAG" --notes-file "$NOTES"
fi

echo
echo "==> 完成！仓库地址："
gh repo view "$REPO_NAME" --json url -q .url
