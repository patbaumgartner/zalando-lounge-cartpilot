// Patchright browser server.
//
// Patchright is a patched, undetected build of Playwright. It has no Java
// binding (see https://github.com/Kaliiiiiiiiii-Vinyzu/patchright), so the Java
// application connects to this server over the Playwright wire protocol via
// BrowserType.connect(wsEndpoint).
//
// Two deployment modes:
//   1. Docker container (recommended): fixed host/port/ws-path so the Java side
//      can connect to a deterministic endpoint (ws://localhost:3000/cartpilot).
//   2. Local Node sidecar: no port/ws-path env -> a random endpoint is chosen
//      and printed on stdout as `WS_ENDPOINT=ws://...` for the Java side to read.
//
// Environment:
//   PATCHRIGHT_HEADLESS  "true" (default) | "false"
//   PATCHRIGHT_HOST      bind host (default 127.0.0.1; use 0.0.0.0 in Docker)
//   PATCHRIGHT_PORT      fixed port (default: random free port)
//   PATCHRIGHT_WS_PATH   fixed websocket path (default: random GUID)
//   PATCHRIGHT_ENABLE_HTTP2   "true" to allow HTTP/2 (default: forced HTTP/1.1)

const { chromium } = require('patchright');

async function main() {
    const headless = process.env.PATCHRIGHT_HEADLESS !== 'false';
    const host = process.env.PATCHRIGHT_HOST || undefined;
    const port = process.env.PATCHRIGHT_PORT ? Number(process.env.PATCHRIGHT_PORT) : undefined;
    const wsPath = process.env.PATCHRIGHT_WS_PATH || undefined;

    // Keep the argument list minimal: Patchright's stealth relies on NOT
    // re-introducing automation-revealing flags. Only keep what the runtime needs
    // (sandbox off for containers/WSL).
    const args = [
        '--no-sandbox',
        '--disable-setuid-sandbox',
        '--disable-dev-shm-usage',
    ];

    // Zalando's edge breaks Chromium's HTTP/2: navigating to /login fails with
    // net::ERR_HTTP2_PROTOCOL_ERROR on every attempt (reproduced 2026-08), so
    // HTTP/1.1 stays forced even though it costs the h2 fingerprint. Set
    // PATCHRIGHT_ENABLE_HTTP2=true to re-test once the site tolerates h2.
    if (process.env.PATCHRIGHT_ENABLE_HTTP2 !== 'true') {
        args.push('--disable-http2');
    }

    const options = { headless, args };
    if (host) options.host = host;
    if (port) options.port = port;
    if (wsPath) options.wsPath = wsPath;

    const server = await chromium.launchServer(options);

    // Signal readiness. For Docker we also log a host-reachable URL hint.
    process.stdout.write('WS_ENDPOINT=' + server.wsEndpoint() + '\n');

    const shutdown = async () => {
        try {
            await server.close();
        } catch (_) {
            // ignore
        }
        process.exit(0);
    };

    process.on('SIGTERM', shutdown);
    process.on('SIGINT', shutdown);
}

main().catch((err) => {
    process.stderr.write('PATCHRIGHT_ERROR=' + (err && err.stack ? err.stack : String(err)) + '\n');
    process.exit(1);
});
