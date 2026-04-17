#!/bin/sh
# ==============================================================================
# ZeroClaw entrypoint
#
# On Render (no bind mount): decodes and extracts the zeroclaw config tarball
# from /etc/secrets/zeroclaw-config.tar.gz.b64 into /zeroclaw-data/.zeroclaw/
# before starting the gateway.
#
# The tarball should mirror the expected ZeroClaw directory structure:
#   config.toml
#   workspace/
#     skills/
#       gmail/SKILL.md
#       linkedin/SKILL.md
#       ...
#
# To generate the secret file from your local config folder, run:
#   tar -czf - -C /path/to/zeroclaw-config . | base64 > zeroclaw-config.tar.gz.b64
# Then paste the contents of zeroclaw-config.tar.gz.b64 into the Render secret file.
#
# Locally (bind mount present): /etc/secrets/ won't exist, nothing is copied.
# ==============================================================================

SECRETS_DIR="/etc/secrets"
ZEROCLAW_DIR="/zeroclaw-data/.zeroclaw"
SECRET_BUNDLE="${SECRETS_DIR}/zeroclaw-config.tar.gz.b64"

if [ -f "${SECRET_BUNDLE}" ]; then
    echo "[entrypoint] Extracting zeroclaw config bundle from secret file"
    mkdir -p "${ZEROCLAW_DIR}"
    base64 -d "${SECRET_BUNDLE}" | tar -xz -C "${ZEROCLAW_DIR}"
    echo "[entrypoint] Config bundle extracted successfully"
fi

# ==============================================================================
# noVNC: start a virtual display so the agent's browser is visible via browser
# at http://localhost:6080/vnc.html  (TEMPORARY — remove after browser testing)
# ==============================================================================
export DISPLAY=:99
Xvfb :99 -screen 0 1280x900x24 -ac &
sleep 1
x11vnc -display :99 -nopw -forever -shared -rfbport 5900 &
websockify --web /usr/share/novnc 6080 localhost:5900 &
# ==============================================================================

exec zeroclaw gateway
