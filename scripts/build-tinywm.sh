#!/usr/bin/env bash
# Cross-compile tinywm (the launcher's minimal X11 window manager) for the
# PRoot rootfs.
#
# The runtime is Ubuntu arm64, so we build with the host's aarch64-linux-gnu-gcc
# (Debian/Ubuntu: apt-get install gcc-aarch64-linux-gnu) and link straight
# against the rootfs' own libX11 — the rootfs ships no -dev packages, and Xlib
# headers are architecture independent.
#
# Usage:
#   scripts/build-tinywm.sh
#
# Environment overrides:
#   SYSROOT  extracted rootfs to take libX11 from (default /tmp/opencode/rfs)
#   OUT      output binary (default appgo/src/main/assets/scripts/tinywm)
#   CC       cross compiler  (default aarch64-linux-gnu-gcc)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/appgo/src/main/native/tinywm.c"
OUT="${OUT:-$ROOT/appgo/src/main/assets/scripts/tinywm}"
SYSROOT="${SYSROOT:-/tmp/opencode/rfs}"
CC="${CC:-aarch64-linux-gnu-gcc}"

LIB_DIR=""
for d in "$SYSROOT/usr/lib/aarch64-linux-gnu" "$SYSROOT/lib/aarch64-linux-gnu"; do
  if [ -e "$d/libX11.so.6" ]; then
    LIB_DIR="$d"
    break
  fi
done

[ -f "$SRC" ] || { echo "missing $SRC"; exit 1; }
command -v "$CC" >/dev/null || { echo "$CC not found (apt-get install gcc-aarch64-linux-gnu)"; exit 1; }
[ -n "$LIB_DIR" ] || { echo "libX11.so.6 not found under $SYSROOT (extract the rootfs first)"; exit 1; }

echo ">>> compiling $SRC"
"$CC" -O2 -Wall -Wextra -o "$OUT" "$SRC" \
  -Wl,-rpath,/usr/lib/aarch64-linux-gnu \
  -Wl,-rpath-link,"$LIB_DIR" \
  "$LIB_DIR/libX11.so.6"

if command -v aarch64-linux-gnu-strip >/dev/null; then
  aarch64-linux-gnu-strip "$OUT"
fi

echo ">>> wrote $OUT ($(stat -c%s "$OUT") bytes)"
echo ">>> NEEDED:"
aarch64-linux-gnu-readelf -d "$OUT" | grep NEEDED || true
