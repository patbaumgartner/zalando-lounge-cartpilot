# CartPilot Test Playbook

Purpose: fast, repeatable local verification of login, session persistence, and scan behavior.

Scope:
- Dev endpoints
- Playwright authentication bootstrap
- Session reuse in headless mode
- Manual scan trigger
- Manual full-cart clear command
- Common failure triage

## 1. Preflight

1. Confirm app compiles:
```bash
./mvnw -q -DskipTests compile
```
Expected:
- Command exits with code 0.

2. Confirm port 8080 is free:
```bash
ss -ltn '( sport = :8080 )' | cat
```
Expected:
- No listener on 8080 before app start.

3. Ensure config source exists:
- This project imports .env in dev profile.
- Do not source .env in zsh if the file is not shell-safe.

## 2. Start Modes

### A) Headed bootstrap mode (recommended first run)
Use this to establish a valid browser session file.

```bash
PLAYWRIGHT_HEADLESS=false \
ZALANDO_HEADED_LOGIN_FALLBACK_ENABLED=true \
./mvnw spring-boot:run
```

Expected startup signals in logs:
- Launching Chromium (headless=false)
- Tomcat started on port 8080

### B) Default headless mode
Use this after session bootstrap to test unattended behavior.

```bash
./mvnw spring-boot:run
```

Expected startup signals in logs:
- Launching Chromium (headless=true)
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

### TC-03 Auth bootstrap endpoint
Command:
```bash
curl -sS -i -X POST http://localhost:8080/dev/browser/auth/bootstrap
```
Expected success:
- HTTP 200
- ok=true
- sessionFileExists=true

Expected failure (environment/challenge case):
- HTTP 500
- ok=false
- error contains Playwright timeout or navigation error
- failureCategory contains one of AUTH_INVALID, CHALLENGE, NETWORK, TIMEOUT, UNKNOWN
- diagnosticsPath is present when ZALANDO_NETWORK_DIAGNOSTICS_ENABLED=true

### TC-04 Session file created
Command:
```bash
ls -l session/state.json
```
Expected:
- File exists and size > 0

### TC-05 Manual scan trigger
Command:
```bash
curl -sS -i -X POST http://localhost:8080/dev/telegram/command \
  -H 'Content-Type: application/json' \
  -d '{"text":"/scan","asAdmin":true}'
```
Expected immediate response:
- HTTP 200
- ok=true
- replies contains "Starting manual scan"

Expected background logs:
- Starting daily campaign scan
- Authentication success or session validity line
- Fetch/scrape progress, or a clear failure stacktrace

### TC-06 Manual clear cart trigger
Command:
```bash
curl -sS -i -X POST http://localhost:8080/dev/telegram/command \
  -H 'Content-Type: application/json' \
  -d '{"text":"/clear","asAdmin":true}'
```
Expected immediate response:
- HTTP 200
- ok=true
- replies contains "Cleared cart"

## 4. Practical Run Sequence

Use this exact order for a clean verification:

1. Start headed bootstrap mode.
2. Run TC-01.
3. Run TC-03 until it returns success.
4. Run TC-04.
5. Run TC-05 and inspect logs.
6. Stop app.
7. Start default headless mode.
8. Run TC-05 again.
9. Compare headed vs headless behavior.

## 5. Known Behaviors and Triage

### Symptom: HTTP2 protocol error on login navigation
Action:
- Ensure Chromium launch args include --disable-http2.
- Check [src/main/java/com/patbaumgartner/zalando/lounge/cartpilot/config/PlaywrightConfiguration.java](src/main/java/com/patbaumgartner/zalando/lounge/cartpilot/config/PlaywrightConfiguration.java).

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
2. Run auth bootstrap endpoint once.
3. Trigger /scan once.
4. Restart in headless mode.
5. Trigger /scan again.

This gives a high-signal regression check for login, session reuse, and scan flow.