#!/bin/sh
# Deterministic startup for the Patchright (undetected Chromium) browser server.
#
# We must run a *headed* browser under a virtual framebuffer (Xvfb) so Zalando's
# bot wall is bypassed. Using `xvfb-run` as the container entrypoint proved racy:
# on cold start the browser server occasionally launched before the X display
# was ready and exited, leaving Xvfb up but no `node` process (and no logs).
#
# Instead we start Xvfb explicitly, wait until its socket is ready, export
# DISPLAY, then `exec node` so the server becomes the main process — its stdout
# (the `WS_ENDPOINT=` readiness line) is visible via `docker logs`, and Chromium
# always has a ready display to attach to.
set -e

DISPLAY_NUM="${PATCHRIGHT_DISPLAY_NUM:-99}"
SCREEN_GEOMETRY="${PATCHRIGHT_SCREEN:-1920x1080x24}"

# Remove any stale lock from a previous run so Xvfb can claim the display.
rm -f "/tmp/.X${DISPLAY_NUM}-lock" 2>/dev/null || true

Xvfb ":${DISPLAY_NUM}" -screen 0 "${SCREEN_GEOMETRY}" -nolisten tcp &
XVFB_PID=$!

# Wait (up to ~15s) for the X11 socket to appear before launching the browser.
i=0
while [ ! -e "/tmp/.X11-unix/X${DISPLAY_NUM}" ]; do
    i=$((i + 1))
    if [ "$i" -gt 150 ]; then
        echo "Xvfb failed to start on display :${DISPLAY_NUM}" >&2
        exit 1
    fi
    sleep 0.1
done

export DISPLAY=":${DISPLAY_NUM}"

# Clean up Xvfb when the server exits.
trap 'kill "$XVFB_PID" 2>/dev/null || true' EXIT INT TERM

exec node server.js
