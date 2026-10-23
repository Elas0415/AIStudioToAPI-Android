#!/usr/bin/env bash
# Build the runtime rootfs asset for the :appgo module (Go backend variant).
#
# Rather than building an Ubuntu image from scratch we reuse the proven
# PRoot/Ubuntu 24.04 arm64 rootfs bundled for :app — it already contains
# everything expensive and fiddly to build on a phone:
#   - Camoufox (arm64)          -> /opt/camoufox
#   - Xvfb, x11vnc, websockify  -> the VNC login window
#   - python3, glibc, fonts     -> runtime deps
# We only strip caches and drop in the upstream Go backend.
#
# Usage:
#   scripts/build-rootfs-go.sh [VERSION]
#     VERSION  upstream tag to fetch (default: latest release), e.g. v0.2.12
#
# Environment overrides:
#   BASE_ROOTFS  base rootfs.tar.zst        (default app/src/main/assets/rootfs.tar.zst)
#   BACKEND_BIN  prebuilt aistudio2api bin  (skips the download)
#   CAMOUFOX_DIR extracted Camoufox 152 dir (replaces the :app rootfs's 135 build)
#   OUT          output rootfs.tar.zst      (default appgo/src/main/assets/rootfs.tar.zst)
#   WORK         scratch directory          (default /tmp/opencode/rootfs-go-build)
#   ZSTD_LEVEL   zstd compression level     (default 19)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BASE_ROOTFS="${BASE_ROOTFS:-$ROOT/app/src/main/assets/rootfs.tar.zst}"
OUT="${OUT:-$ROOT/appgo/src/main/assets/rootfs.tar.zst}"
WORK="${WORK:-/tmp/opencode/rootfs-go-build}"
ZSTD_LEVEL="${ZSTD_LEVEL:-19}"
REPO="Mag1cFall/AIStudio2API"

VERSION="${1:-${VERSION:-}}"
BACKEND_BIN="${BACKEND_BIN:-}"

log() { printf '\n>>> %s\n' "$*"; }

command -v zstd >/dev/null || { echo "zstd is required"; exit 1; }
command -v tar  >/dev/null || { echo "tar is required"; exit 1; }
[ -f "$BASE_ROOTFS" ] || { echo "base rootfs not found: $BASE_ROOTFS"; exit 1; }

# ---------------------------------------------------------------------------
# 1. Obtain the upstream Go backend (static linux/arm64 single binary).
# ---------------------------------------------------------------------------
RELEASE_DIR=""
if [ -z "$BACKEND_BIN" ]; then
  if [ -z "$VERSION" ]; then
    log "Resolving latest $REPO release"
    VERSION="$(curl -fsSL "https://api.github.com/repos/$REPO/releases/latest" \
      | python3 -c 'import json,sys; print(json.load(sys.stdin)["tag_name"])')"
  fi
  log "Fetching $REPO $VERSION (linux-arm64)"
  RELEASE_DIR="$WORK/release"
  rm -rf "$RELEASE_DIR"
  mkdir -p "$RELEASE_DIR"
  ASSET="aistudio2api-${VERSION}-linux-arm64.tar.gz"
  curl -fsSL -o "$RELEASE_DIR/$ASSET" \
    "https://github.com/$REPO/releases/download/$VERSION/$ASSET"
  tar -xzf "$RELEASE_DIR/$ASSET" -C "$RELEASE_DIR"
  BACKEND_BIN="$(find "$RELEASE_DIR" -type f -name aistudio2api | head -1)"
  [ -n "$BACKEND_BIN" ] || { echo "aistudio2api not found in release tarball"; exit 1; }
  [ -n "$VERSION" ] || VERSION="unknown"
fi

log "Backend binary: $BACKEND_BIN ($(du -h "$BACKEND_BIN" | cut -f1))"
[ -n "$VERSION" ] || VERSION="unknown"
echo "Backend version: $VERSION"

# Sanity: must be a static linux/arm64 executable.
file "$BACKEND_BIN" | grep -q 'ARM aarch64' || {
  echo "unexpected backend binary architecture: $(file -b "$BACKEND_BIN")"; exit 1; }

# ---------------------------------------------------------------------------
# 2. Unpack the base rootfs.
# ---------------------------------------------------------------------------
ROOTFS="$WORK/rootfs"
if [ ! -e "$ROOTFS/opt/camoufox" ]; then
  log "Extracting base rootfs (approx 2.3 GB, this takes a few minutes)"
  rm -rf "$ROOTFS"
  mkdir -p "$ROOTFS"
  zstd -dc "$BASE_ROOTFS" | tar -x -C "$ROOTFS" --no-same-owner 2>&1 \
    | grep -vE "Cannot (mknod|utime|chown|change ownership)" || true
  [ -e "$ROOTFS/opt/camoufox/camoufox-bin" ] || { echo "base rootfs incomplete"; exit 1; }
else
  log "Reusing already-extracted rootfs at $ROOTFS"
fi

# The :app base rootfs ships Camoufox 135, but AIStudio2API v0.2.13 aligns its
# fingerprint/TLS transport with 152.0.4-beta.29. A 135 page makes AI Studio
# return HTTP 403 / code 7, so replace it when CAMOUFOX_DIR is provided.
if [ -n "${CAMOUFOX_DIR:-}" ]; then
  [ -x "$CAMOUFOX_DIR/camoufox-bin" ] || {
    echo "CAMOUFOX_DIR has no camoufox-bin: $CAMOUFOX_DIR"; exit 1; }
  log "Replacing Camoufox with $CAMOUFOX_DIR"
  rm -rf "$ROOTFS/opt/camoufox"
  cp -a "$CAMOUFOX_DIR" "$ROOTFS/opt/camoufox"
fi
CAMOUFOX_VERSION="$(sed -n 's/^Version=//p' \
  "$ROOTFS/opt/camoufox/application.ini" 2>/dev/null | head -1)"
echo "Camoufox version: ${CAMOUFOX_VERSION:-unknown}"
[ "$CAMOUFOX_VERSION" = "152.0.4-beta.29" ] || \
  log "WARNING: expected Camoufox 152.0.4-beta.29; the current build may 403"

# ---------------------------------------------------------------------------
# 3. Strip build/runtime caches we do not need (keeps the APK smaller).
# ---------------------------------------------------------------------------
log "Stripping caches"
rm -rf "$ROOTFS/root/.npm/_cacache" \
       "$ROOTFS/var/cache/apt" \
       "$ROOTFS/var/lib/apt/lists" \
       "$ROOTFS/tmp/"* 2>/dev/null || true

# ---------------------------------------------------------------------------
# 4. Install the Go backend.
# ---------------------------------------------------------------------------
log "Installing Go backend into /opt/aistudio2api"
BACKEND_DIR="$ROOTFS/opt/aistudio2api"
mkdir -p "$BACKEND_DIR"
install -m 0755 "$BACKEND_BIN" "$BACKEND_DIR/aistudio2api"
printf '%s' "$VERSION" > "$BACKEND_DIR/VERSION"
if [ -n "$RELEASE_DIR" ] && [ -f "$RELEASE_DIR"/*/README.md ]; then
  cp "$RELEASE_DIR"/*/README.md "$BACKEND_DIR/README.md" 2>/dev/null || true
fi
mkdir -p "$BACKEND_DIR/auth" "$BACKEND_DIR/runtime"

# The launcher writes .env on the device; ship an example for reference.
cat > "$BACKEND_DIR/.env.example" <<'EOF'
# 由 Android 启动器在首次启动时生成 .env，这里仅作参考。
AISTUDIO_AUTH_STATES=auth
LISTEN_ADDR=127.0.0.1:2048
PROXY_API_KEY=123456
AUTO_START=false
EOF

# ---------------------------------------------------------------------------
# 5. Repack.
# ---------------------------------------------------------------------------
log "Repacking rootfs -> $OUT (zstd -$ZSTD_LEVEL -T0)"
mkdir -p "$(dirname "$OUT")"
TMP_OUT="$OUT.partial"
tar -c -C "$ROOTFS" --owner=0 --group=0 --numeric-owner -p . \
  | zstd "-$ZSTD_LEVEL" -T0 -f -o "$TMP_OUT"
mv -f "$TMP_OUT" "$OUT"

log "Done"
ls -lh "$OUT"
