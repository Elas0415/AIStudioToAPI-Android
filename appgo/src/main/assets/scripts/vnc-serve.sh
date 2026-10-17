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
# Virtual desktop geometry, passed in by the app as the phone's *portrait*
# aspect ratio. Matching the phone's shape means noVNC's fit-to-view wastes as
# little of the login screen as possible; keeping the short side wide (1080px)
# means a fresh Camoufox window, which picks its own size, cannot end up larger
# than the desktop and get clipped. The default keeps the script usable alone.
SCREEN="${VNC_SCREEN:-1080x1920}"

# Clean up anything left over from a previous attempt.
pkill -f "Xvfb ${DISPLAY_NUM}" 2>/dev/null
pkill -f "x11vnc .*${DISPLAY_NUM}" 2>/dev/null
pkill -f "websockify .*6080" 2>/dev/null
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
