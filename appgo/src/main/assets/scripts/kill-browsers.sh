#!/bin/bash
# Stops the *login* browser left over from a previous attempt.
#
# The backend opens a headful Camoufox for 「浏览器登录」 with
# -wait-for-browser; the long-lived WAA workers are -headless and are left
# alone so a login does not interrupt requests already being served.
#
# The pattern is bracketed so pkill cannot match the shell running this
# script (whose command line would otherwise contain the pattern itself).
pkill -9 -f '[-]wait-for-browser' 2>/dev/null
exit 0
