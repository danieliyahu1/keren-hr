#!/bin/sh
# ==============================================================================
# OpenCode entrypoint
#
# noVNC (TEMPORARY): starts a virtual display so the agent's browser is
# visible via browser at http://localhost:6081/vnc.html
# ==============================================================================

# Start virtual display
export DISPLAY=:99
Xvfb :99 -screen 0 1280x900x24 -ac &
sleep 1
x11vnc -display :99 -nopw -forever -shared -rfbport 5901 &
websockify --web /usr/share/novnc 6081 localhost:5901 &

exec opencode serve --port 4096 --hostname 0.0.0.0
