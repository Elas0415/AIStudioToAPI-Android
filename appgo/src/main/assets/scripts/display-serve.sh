#!/bin/bash
set -e
export HOME=/root
export PATH=/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin
export TMPDIR=/tmp
export LANG=C.UTF-8

DISPLAY_NUM=:99
export DISPLAY="$DISPLAY_NUM"
SCREEN="${VNC_SCREEN:-1280x720}"
WEB_DIR=/opt/display
LOG_DIR=/tmp

pkill -f "Xvfb $DISPLAY_NUM" 2>/dev/null || true
pkill -x x11vnc 2>/dev/null || true
pkill -f "websockify" 2>/dev/null || true
pkill -f "display/server.js" 2>/dev/null || true
pkill -x tinywm 2>/dev/null || true
rm -f "/tmp/.X99-lock" "/tmp/.X11-unix/X99" 2>/dev/null || true
sleep 0.3

mkdir -p /tmp/.X11-unix
chmod 1777 /tmp/.X11-unix 2>/dev/null || true

Xvfb "$DISPLAY_NUM" -screen 0 "${SCREEN}x24" +extension RANDR -nolisten tcp -nolock -ac >"$LOG_DIR/xvfb.log" 2>&1 &
XVFB_PID=$!
for _ in $(seq 1 50); do
  [ -S "/tmp/.X11-unix/X99" ] && break
  kill -0 "$XVFB_PID" 2>/dev/null || { echo "[display] Xvfb exited"; tail -n 20 "$LOG_DIR/xvfb.log"; exit 1; }
  sleep 0.2
done
echo "[display] Xvfb ready on $DISPLAY ($SCREEN)"

x11vnc -display "$DISPLAY_NUM" -rfbport 5901 -forever -nopw -shared -quiet -repeat -noshm -noxdamage -noxrecord -noxfixes -noxinerama -noscr -nowf -verbose >"$LOG_DIR/x11vnc.log" 2>&1 &
X11VNC_PID=$!
READY=0
for _ in $(seq 1 75); do
  if ! kill -0 "$X11VNC_PID" 2>/dev/null; then
    wait "$X11VNC_PID"; CODE=$?
    echo "[display] x11vnc exited (code $CODE)"
    tail -n 40 "$LOG_DIR/x11vnc.log"
    exit 1
  fi
  if (echo >/dev/tcp/127.0.0.1/5901) 2>/dev/null; then READY=1; break; fi
  sleep 0.2
done
[ "$READY" = "1" ] || echo "[display] WARNING: x11vnc port not ready"
echo "[display] x11vnc ready on 127.0.0.1:5901"

websockify 127.0.0.1:6080 127.0.0.1:5901 >"$LOG_DIR/websockify.log" 2>&1 &
for _ in $(seq 1 50); do
  (echo >/dev/tcp/127.0.0.1/6080) 2>/dev/null && break
  sleep 0.2
done
echo "[display] websockify ready on 127.0.0.1:6080"

node "$WEB_DIR/server.js" >"$LOG_DIR/display-server.log" 2>&1 &
for _ in $(seq 1 50); do
  (echo >/dev/tcp/127.0.0.1/6081) 2>/dev/null && break
  sleep 0.2
done
echo "[display] client ready on 127.0.0.1:6081"

TINYWM_VERBOSE=1 tinywm >"$LOG_DIR/tinywm.log" 2>&1 &
sleep 0.4
pgrep -x tinywm >/dev/null 2>&1 || echo "[display] WARNING: tinywm is not running"

echo "[display] ready"
wait
