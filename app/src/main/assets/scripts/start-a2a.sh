#!/bin/bash
# AIStudioToAPI launcher inside PRoot Ubuntu
set -x
export HOME=/root
export PATH=/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin
export TMPDIR=/tmp
export NODE_ENV=production
export PORT=7860
export HOST=127.0.0.1
export CAMOUFOX_EXECUTABLE_PATH=/opt/camoufox/camoufox
export DISPLAY=:99
export LANG=C.UTF-8
export PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1

# Load runtime env written by the Android app (API_KEYS, HOST, PORT).
if [ -f /data/a2a.env ]; then
  set -a
  . /data/a2a.env
  set +a
fi

cd /opt/a2a
exec /usr/local/bin/node main.js
