#!/bin/bash
# AIStudio2API (Go backend) launcher inside the PRoot Ubuntu rootfs.
#
# Runs the single static binary from /opt/aistudio2api. The binary reads .env
# from its working directory, so we cd there first; auth states live in
# /opt/aistudio2api/auth and logs/DB in /opt/aistudio2api/runtime.
set -x

export HOME=/root
export PATH=/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin
export TMPDIR=/tmp
export LANG="${LANG:-C.UTF-8}"
export TZ="${TZ:-UTC}"

# The slim rootfs has no ca-certificates package; the launcher rebuilds this
# bundle on every start (Mozilla roots + the phone's own CA store).
export SSL_CERT_FILE=/etc/ssl/certs/ca-certificates.crt
export SSL_CERT_DIR=/etc/ssl/certs

# The management UI's 「浏览器登录」 drives a *visible* Camoufox, so it needs the
# virtual display that vnc-serve.sh manages. The Android launcher passes this in;
# the default keeps the script usable on its own.
export DISPLAY="${DISPLAY:-:99}"

# Camoufox is managed by the backend itself: with CAMOUFOX_PATH unset it looks in
# runtime/camoufox, validates application.ini against the version it requires and
# downloads the matching release when it is missing or outdated. The launcher links
# the copy bundled in the rootfs into that path, so the first run needs no download
# and an upstream bump (e.g. 152 -> 158) is fetched by the console's 重试 button.
# Firefox's content sandbox cannot initialise under PRoot (SELinux + missing
# namespaces) and would SIGSEGV every renderer.
export MOZ_DISABLE_CONTENT_SANDBOX=1
export MOZ_DISABLE_GMP_SANDBOX=1

cd /opt/aistudio2api || exit 1

# --open-ui=false: never try to xdg-open a browser (there is no desktop here).
exec ./aistudio2api --open-ui=false
