#!/bin/bash
# Virtual desktop for the backend's headful Camoufox login window.
#
#   Xvnc :99  (TigerVNC: X server + RFB server in one process)
#        -> websockify 127.0.0.1:6080 (serves the in-app noVNC client)
#
# Xvnc replaces the Xvfb + x11vnc pair this stack used to run: one process
# instead of two, no framebuffer polling (x11vnc had to read and compare the
# whole screen), damage-driven updates from inside the server, and RANDR so the
# desktop geometry is a real X screen size rather than a hint.
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
# Virtual desktop geometry, chosen in the app's 「显示分辨率」 menu (default:
# 1280x720 — the app shows the login window in landscape, so the desktop is
# landscape too). This is the *framebuffer* size — what noVNC shows — not a box
# the browser has to fit into: tinywm (started below) fills every window with
# the screen, so Camoufox can request any size it likes without being clipped at
# the framebuffer edge.
SCREEN="${VNC_SCREEN:-1280x720}"

# Clean up anything left over from a previous attempt.
pkill -f "Xvnc ${DISPLAY_NUM}" 2>/dev/null
pkill -f "Xtigervnc ${DISPLAY_NUM}" 2>/dev/null
pkill -x Xtigervnc 2>/dev/null
pkill -f "websockify .*6080" 2>/dev/null
pkill -x tinywm 2>/dev/null
rm -f "/tmp/.X99-lock" "/tmp/.X11-unix/X99" 2>/dev/null
sleep 0.5

# Xvnc needs the abstract/unix socket dir; create it explicitly because Android
# may hand us an empty /tmp.
mkdir -p /tmp/.X11-unix
chmod 1777 /tmp/.X11-unix 2>/dev/null

# Xvnc is deployed by RuntimeEnv from assets/xvnc (TigerVNC 1.13.1, arm64).
# Prefer the distro-style symlink, but fall back to the real binary name in case
# the rootfs could not carry a symlink.
VNC_SERVER=Xvnc
command -v Xvnc >/dev/null 2>&1 || VNC_SERVER=Xtigervnc
#
# The invocation below is Xvnc's own option set:
#
#   -nolock          Android SELinux forbids Xorg's lock link() inside app
#                    storage. proot runs us as fake-root, so Xvnc accepts it.
#   -SecurityTypes None  no VNC password: the port is loopback-only and
#                    websockify is the only client.
#   -localhost       bind loopback only; never expose RFB to the LAN.
#   -UseIPv6=0       IPv4 loopback only: websockify dials 127.0.0.1, and this
#                    keeps Xvnc from failing when a kernel has IPv6 disabled.
#   -AlwaysShared    a reconnecting noVNC (rotation, overlay re-show) must not
#                    be refused because a stale session is still registered.
#   -AcceptSetDesktopSize=0  the app owns the geometry (VNC_SCREEN); a client
#                    resize would leave tinywm's full-screen windows stale.
#   -FrameRate 30    the login page is mostly static; 30 fps halves the encode
#                    cost on a phone without a visible difference.
"${VNC_SERVER}" "${DISPLAY_NUM}" \
  -geometry "${SCREEN}" \
  -depth 24 \
  -nolock \
  -rfbport 5900 \
  -localhost \
  -UseIPv6=0 \
  -SecurityTypes None \
  -AlwaysShared \
  -AcceptSetDesktopSize=0 \
  -FrameRate 30 \
  -desktop "AIStudio2API" >/tmp/xvnc.log 2>&1 &
XVNC_PID=$!
echo "[vnc] Xvnc started (pid ${XVNC_PID}) for ${SCREEN}"

# Wait for the RFB listener instead of sleeping blindly: Xvnc needs a moment to
# bring up the screen and compile the keymap.
for _ in $(seq 1 50); do
  if ! kill -0 "${XVNC_PID}" 2>/dev/null; then
    echo "[vnc] ERROR: Xvnc exited during startup:"
    tail -n 20 /tmp/xvnc.log
    exit 1
  fi
  if (echo >/dev/tcp/127.0.0.1/5900) 2>/dev/null; then
    break
  fi
  sleep 0.2
done

# tinywm is the ~66 KB window manager built for exactly this job (source:
# appgo/src/main/native/tinywm.c, built by scripts/build-tinywm.sh, deployed to
# /usr/local/bin by RuntimeEnv). It fills every application window with the
# screen, so the headful Camoufox login window fills the desktop instead of
# being drawn partly outside the framebuffer — which is what noVNC would
# otherwise show, as a clipped browser.
#
# It has to cope with the way Firefox/GTK starts up: the main window is mapped
# as a 10x10 placeholder and only then resized to the real geometry (verified
# with this rootfs' Camoufox: `0x200001 "Firefox" 10x10+10+10`). tinywm adopts
# such a window on the ConfigureRequest that grows it, and a 1 s watchdog
# re-scans the root and re-asserts the full-screen geometry, so the browser is
# never left larger than the desktop. Override-redirect windows (menus,
# tooltips) and helper windows under 100x100 are ignored.
TINYWM_VERBOSE=1 tinywm >/tmp/tinywm.log 2>&1 &
sleep 0.6
if pgrep -x tinywm >/dev/null 2>&1; then
  echo "[vnc] tinywm running: windows are forced to fill ${SCREEN}"
else
  # Without a WM the browser keeps its own (usually wider) geometry and noVNC
  # shows it clipped at the framebuffer edge.
  echo "[vnc] WARNING: tinywm is not running (see /tmp/tinywm.log) - the login" \
       "window may be clipped"
fi

# noVNC speaks WebSocket, Xvnc speaks raw RFB, so websockify bridges the two.
# It is served to the in-app WebView only; bind to loopback for safety.
websockify --web /opt/novnc 127.0.0.1:6080 127.0.0.1:5900 >/tmp/websockify.log 2>&1 &
echo "[vnc] websockify started on :6080"

echo "[vnc] ready"
# Keep the container process alive so the Android side can track/stop it.
wait
