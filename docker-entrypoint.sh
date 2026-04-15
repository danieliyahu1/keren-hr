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

exec zeroclaw gateway
