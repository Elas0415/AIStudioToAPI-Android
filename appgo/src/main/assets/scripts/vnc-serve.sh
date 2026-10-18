#!/bin/bash
# Virtual desktop for the backend's headful Camoufox login window.
#
#   Xvfb :99  ->  x11vnc :5900  ->  websockify :6080 (serves noVNC from /opt/novnc)
#
# The Go backend itself decides when to open a browser: the management UI's
# 「浏览器登录」 button (POST /api/accounts) drives a *visible* Camoufox. Because
# the backend runs with DISPLAY=:99 this stack only has to be up before that
# happens, so the Android service starts it together with the backend and the
# app renders http://127.0.0.1:6080/login.html in a WebView while the request is
# in flight.
#
# Blocks until killed.
export HOME=/root
export PATH=/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin
export TMPDIR=/tmp
export LANG=C.UTF-8

DISPLAY_NUM=:99
export DISPLAY="${DISPLAY_NUM}"
# Virtual desktop geometry, chosen in the app's 「显示分辨率」 menu (default: the
# phone's own portrait aspect). This is the *framebuffer* size — what noVNC
# shows — not a box the browser has to fit into: tinywm (started below) fills
# every window with the screen, so Camoufox can request any size it likes
# without being clipped at the framebuffer edge.
SCREEN="${VNC_SCREEN:-1080x1920}"

# Clean up anything left over from a previous attempt.
pkill -f "Xvfb ${DISPLAY_NUM}" 2>/dev/null
pkill -f "x11vnc .*${DISPLAY_NUM}" 2>/dev/null
pkill -f "websockify .*6080" 2>/dev/null
pkill -x tinywm 2>/dev/null
rm -f "/tmp/.X99-lock" "/tmp/.X11-unix/X99" 2>/dev/null
sleep 0.5

# Xvfb needs the abstract/unix socket dir; create it explicitly because Android
# may hand us an empty /tmp.
mkdir -p /tmp/.X11-unix
chmod 1777 /tmp/.X11-unix 2>/dev/null

# /usr/local/bin/Xvfb is a wrapper that appends -nolock (Android SELinux
# forbids Xorg's lock-link syscall).
Xvfb "${DISPLAY_NUM}" -screen 0 "${SCREEN}x24" -nolock >/tmp/xvfb.log 2>&1 &
echo "[vnc] Xvfb started"
sleep 1.5

# tinywm is a ~70 KB window manager built for exactly this job (source:
# appgo/src/main/native/tinywm.c, built by scripts/build-tinywm.sh, deployed to
# /usr/local/bin by RuntimeEnv). It fills every top-level window with the screen,
# so the headful Camoufox login window always fills the desktop instead of being
# drawn partly outside the framebuffer — which is what noVNC would otherwise
# show, as a clipped browser. It ignores override-redirect windows (menus,
# tooltips) and small helper windows, and keeps full-screen even when the page
# asks to resize. Not fatal if it fails: the display still works, just clipped.
tinywm >/tmp/tinywm.log 2>&1 &
echo "[vnc] tinywm started"
sleep 0.5

# /usr/local/bin/x11vnc is a wrapper that appends -noshm. -localhost keeps the
# raw VNC port off the LAN; only websockify (bound to loopback) exposes it.
x11vnc -display "${DISPLAY_NUM}" -rfbport 5900 -localhost -shared -forever -nopw \
  -noxdamage -noshm -quiet >/tmp/x11vnc.log 2>&1 &
echo "[vnc] x11vnc started"
sleep 1

# noVNC is served to the in-app WebView only; bind to loopback for safety.
websockify --web /opt/novnc 127.0.0.1:6080 127.0.0.1:5900 >/tmp/websockify.log 2>&1 &
echo "[vnc] websockify started on :6080"

echo "[vnc] ready"
# Keep the container process alive so the Android side can track/stop it.
wait
