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
export LANG=C.UTF-8
export TZ="${TZ:-UTC}"

# The management UI's 「浏览器登录」 drives a *visible* Camoufox, so it needs the
# virtual display that vnc-serve.sh manages. The Android launcher passes this in;
# the default keeps the script usable on its own.
export DISPLAY="${DISPLAY:-:99}"

# Reuse the Camoufox that ships with the bundled rootfs.
export CAMOUFOX_PATH=/opt/camoufox/camoufox-bin
# Firefox's content sandbox cannot initialise under PRoot (SELinux + missing
# namespaces) and would SIGSEGV every renderer.
export MOZ_DISABLE_CONTENT_SANDBOX=1
export MOZ_DISABLE_GMP_SANDBOX=1

cd /opt/aistudio2api || exit 1

# --open-ui=false: never try to xdg-open a browser (there is no desktop here).
exec ./aistudio2api --open-ui=false
