# Patchright (undetected Playwright) browser server.
#
# Based on the official Playwright image, which already contains Chromium's
# system libraries plus Xvfb. We install Patchright and its patched Chromium
# on top.
#
# IMPORTANT: Zalando's bot wall blocks *headless* Chromium (navigation stalls at
# "commit"). Patched Chromium only gets through when running *headed*. In a
# container there is no real display, so we run headed under Xvfb (a virtual
# framebuffer) via xvfb-run. This is the standard "undetected browser in Docker"
# pattern.
FROM mcr.microsoft.com/playwright:v1.60.0-jammy

WORKDIR /app

# Install the Patchright npm package.
COPY package.json ./
RUN npm install

# Download Patchright's patched Chromium build.
RUN npx patchright install chromium

COPY server.js ./
COPY entrypoint.sh ./
RUN chmod +x ./entrypoint.sh

# Deterministic, host-reachable endpoint: ws://<host>:3000/cartpilot
# Headed (PATCHRIGHT_HEADLESS=false) under Xvfb so the bot wall is bypassed.
ENV PATCHRIGHT_HEADLESS=false \
    PATCHRIGHT_HOST=0.0.0.0 \
    PATCHRIGHT_PORT=3000 \
    PATCHRIGHT_WS_PATH=cartpilot

EXPOSE 3000

# Start Xvfb, wait for the display, then exec the headed browser server.
# (Explicit startup instead of `xvfb-run` to avoid a cold-start race where the
# server launched before the X display was ready and exited with no logs.)
CMD ["./entrypoint.sh"]
