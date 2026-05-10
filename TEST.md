# CartPilot Test Playbook

Purpose: fast, repeatable local verification of login, session persistence, and scan behavior.

Scope:

- Patchright browser sidecar startup
- Session reuse in headless mode
- Manual scan trigger through Telegram or a temporary scheduler override
- Manual full-cart clear command through Telegram
- Live cart keep-alive (remove + re-add) and clear against the real site (opt-in)
- Common failure triage

## 1. Preflight

1. Confirm app compiles:

```bash
./mvnw -q -DskipTests compile
```

Expected:

- Command exits with code 0.

1. Confirm port 8080 is free:

```bash
ss -ltn '( sport = :8080 )' | cat
```

Expected:

- No listener on 8080 before app start.

1. Ensure config source exists:

- This project imports .env in dev profile.
- Do not source .env in zsh if the file is not shell-safe.

## 2. Start Modes

The app does not launch a browser itself — it connects to the **Patchright** browser server (the `patchright` docker-compose service / `patchright/server.js`). Start it first:

```bash
docker compose up -d patchright        # or, locally: cd patchright && npm install && node server.js
```

### A) Headed bootstrap mode (recommended first run)

Use this to establish a valid browser session file. Run the Patchright server headed (`PATCHRIGHT_HEADLESS=false`) so a real window appears for manual login.

```bash
PLAYWRIGHT_HEADLESS=false \
ZALANDO_HEADED_LOGIN_FALLBACK_ENABLED=true \
./mvnw spring-boot:run
```

Expected startup signals in logs:

- Connecting to Patchright browser server at ws://...
- Tomcat started on port 8080

### B) Default headless mode

Use this after session bootstrap to test unattended behavior.

```bash
./mvnw spring-boot:run
```

Expected startup signals in logs:

- Connecting to Patchright browser server at ws://...
- Tomcat started on port 8080

## 3. Core Testcases

### TC-01 Health endpoint

Command:

```bash
curl -sS -i http://localhost:8080/actuator/health
```

Expected:

- HTTP 200
- JSON status UP

### TC-02 Session file created

Command:

```bash
ls -l session/state.json
```

Expected:

- File exists and size > 0

### TC-03 Manual scan trigger

Preferred command:

- Send `/scan` in the Telegram group as an admin.

Scheduler-only alternative:

```bash
M=$(( ($(date +%M)+1)%60 ))
CARTPILOT_SCHEDULER_SCAN_CRON="0 $M * * * *" ./mvnw spring-boot:run
```

Do not pass a spaced cron value through `-Dspring-boot.run.arguments`; the value is split on spaces.

Expected background logs:

- Starting daily campaign scan
- Authentication success or session validity line
- Fetch/scrape progress, or a clear failure stacktrace

### TC-04 Manual clear cart trigger

Command:

- Send `/clear` in the Telegram group as an admin.

Expected response:

- Group reply contains "Cleared cart"

### TC-05 Live cart keep-alive + clear (opt-in, hits production)

Exercises the full cart lifecycle against the real Zalando Lounge site: it picks an
in-stock article from today's campaigns, adds it, runs the keep-alive refresh (remove +
re-add that resets Zalando's server-side reservation timer), then clears the basket.

Disabled by default — it only runs when `-Dlive.cart.test=true` is passed, so it never
executes during `mvn test` / `verify`.

Prerequisites:

- Patchright server running (default `ws://localhost:3000/cartpilot`; override with `-Dpatchright.ws=...`).
- A valid logged-in `session/state.json` in the working directory.

Command:

```bash
./mvnw -Dtest=PlaywrightBrowserAdapterLiveTest -Dlive.cart.test=true test
```

Expected:

- `addToCart -> true`, item present in the authoritative cart.
- `refreshCartItem` removes and re-adds the item; it remains in the cart afterwards.
- `clearCart` empties the basket.
- Test passes, or is skipped (assumption not met) when no in-stock article is available.

## 4. Practical Run Sequence

Use this exact order for a clean verification:

1. Start headed bootstrap mode.
1. Run TC-01.
1. Confirm TC-02.
1. Run TC-03 and inspect logs.
1. Run TC-04 if cart cleanup needs verification.
1. Stop app.
1. Start default headless mode.
1. Run TC-03 again.
1. Compare headed vs headless behavior.

## 5. Known Behaviors and Triage

### Symptom: HTTP2 protocol error on login navigation

Action:

- Ensure the Patchright server launch args include --disable-http2.
- Check [patchright/server.js](patchright/server.js).

### Symptom: scan times out on network idle during campaign scraping

Action:

- Use DOM/content readiness waits, not NETWORKIDLE, for campaign and product pages.
- Check [src/main/java/com/patbaumgartner/zalando/lounge/cartpilot/adapter/out/browser/CampaignScraper.java](src/main/java/com/patbaumgartner/zalando/lounge/cartpilot/adapter/out/browser/CampaignScraper.java).

### Symptom: headless session validation times out, headed works

Interpretation:

- Environment/challenge behavior differs between headless and headed traffic.

Action:

- Re-bootstrap session in headed mode.
- Re-test headless.
- Keep logs from AuthenticationService and scanner path for comparison.

## 6. Log Signals to Watch

Success indicators:

- Login successful on attempt X/Y, session saved
- Session loaded from file and is valid
- Starting daily campaign scan

Failure indicators:

- Timeout exceeded while navigating to /login or /event
- Login failed after N attempts
- Scan failed with stacktrace

## 7. Cleanup

Stop running app terminal when done.

Optional cleanup:

```bash
rm -f session/state.json
```

Use this to force a fresh login/bootstrap test cycle.

## 8. Next Time Quickstart

Minimal fast path:

1. Start headed bootstrap mode.
1. Confirm `session/state.json` exists.
1. Trigger `/scan` once.
1. Restart in headless mode.
1. Trigger `/scan` again.

This gives a high-signal regression check for login, session reuse, and scan flow.
