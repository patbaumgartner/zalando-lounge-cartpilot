# Zalando Lounge CartPilot

[![CI](https://github.com/patbaumgartner/zalando-lounge-cartpilot/actions/workflows/ci.yml/badge.svg)](https://github.com/patbaumgartner/zalando-lounge-cartpilot/actions/workflows/ci.yml)
[![Release](https://github.com/patbaumgartner/zalando-lounge-cartpilot/actions/workflows/release.yml/badge.svg)](https://github.com/patbaumgartner/zalando-lounge-cartpilot/actions/workflows/release.yml)
[![CodeQL](https://github.com/patbaumgartner/zalando-lounge-cartpilot/actions/workflows/codeql.yml/badge.svg)](https://github.com/patbaumgartner/zalando-lounge-cartpilot/actions/workflows/codeql.yml)
[![Dependency Review](https://github.com/patbaumgartner/zalando-lounge-cartpilot/actions/workflows/dependency-review.yml/badge.svg)](https://github.com/patbaumgartner/zalando-lounge-cartpilot/actions/workflows/dependency-review.yml)
[![Dependabot](https://img.shields.io/badge/dependabot-enabled-025E8C?logo=dependabot)](https://github.com/patbaumgartner/zalando-lounge-cartpilot/security/dependabot)

![Java](https://img.shields.io/badge/Java-25-007396?logo=openjdk)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1.0-6DB33F?logo=springboot)
![Maven](https://img.shields.io/badge/build-Maven-C71A36?logo=apachemaven)
![PostgreSQL](https://img.shields.io/badge/database-PostgreSQL-4169E1?logo=postgresql)
![Playwright](https://img.shields.io/badge/browser-Playwright-2EAD33?logo=playwright)
![Docker Hub](https://img.shields.io/badge/publish-Docker%20Hub-2496ED?logo=docker)

Headless Spring Boot backend that monitors [Zalando Lounge CH](https://www.zalando-lounge.ch) every morning, automatically adds matching items to cart for configured family member profiles, and posts notifications to a shared Telegram group.

No web UI. No admin panel. Everything controlled via Telegram.

## How It Works

1. **06:00 CEST** — The scheduler scrapes active campaigns from Zalando Lounge CH by driving an undetected Chromium browser (**Patchright**) that runs as a separate service; the app connects to it over the Playwright wire protocol.
2. Products are matched against each active profile's brand tiers, gender, sizes, and price caps.
3. **Tier 1 brands** → item is added to cart automatically and a notification is posted to the group.
4. **Tier 2 brands** → collected into a **06:10 morning summary** posted to the group.
5. Every scan ends with a **scan report** (counts, timings, failures) plus a **link list per outcome** — reserved, blocked by bot protection, notify-only, and size-gone — so every matched product is one tap away.
6. Group members tap **[🛍 Buy]** or **[❌ Skip]** inline buttons to act on notifications.
7. Every 15 minutes the cart keep-alive scheduler removes and re-adds each reserved item, which resets Zalando's server-side reservation timer and prolongs the hold (default 20 min timeout, up to 2 h).

### Access control

The bot only obeys the group named by `TELEGRAM_GROUP_CHAT_ID`. Every inbound message and button callback is matched against that chat id, and anything from another chat is ignored and logged. This matters because whoever adds a bot to a chat is that chat's *creator* — and therefore an "admin" by Telegram's own check — so without the binding a stranger could add the bot to their own group and drive `/scan`, `/clear` and `/profile` against your account. If the property is unset the bot answers nothing rather than failing open.

Within the configured group, admin-only commands are additionally gated on Telegram's `getChatMember` status.

### Concurrency

One browser serves four callers: the daily scan, the 15-minute keep-alive, manual `/scan`, and `/clear` and Skip. Playwright objects may only be used by one thread at a time, so every workflow that touches the browser takes a single reentrant lock and holds it for the *whole* workflow — locking per call would still let a `/clear` land between a scan's scrape and its cart adds. The keep-alive skips its cycle rather than queueing: a scan replaces the whole basket, so by the time it finishes there is nothing left to refresh.

### Bot protection

Zalando's basket endpoint sits behind Akamai BotManager. A refused cart add is reported as **blocked**, never as *sold out*: the reservation is kept as `BLOCKED` and the product stays on the link list for a manual grab.

Keep-alive refreshes the hold by removing the item and re-adding it. If the removal itself was refused, the original hold is untouched and the reservation stays `IN_CART` for the next cycle. If the removal succeeded but the re-add failed — including when bot protection refused it — the hold is genuinely gone, so the reservation is marked `EXPIRED` and posted to the link list.

The basket is only cleared once a scan has products to put back, so a failed login, an unreachable browser or a quiet campaign day never discards holds that are still valid. A basket that cannot be *read* releases nothing at all, since an unreadable basket is not an empty one.

Telegram rejects messages over 4096 characters, so every outbound message is split on line boundaries — a scan matching dozens of items arrives as several messages rather than silently vanishing.

## Architecture

The project follows **Hexagonal Architecture** (Ports & Adapters):

```
domain/          Pure Java domain model and port interfaces — zero Spring dependency
application/     Use-case services wired to domain ports
adapter/
  in/
    telegram/    TelegramBotHandler — inbound commands and callback queries
    scheduler/   Cron-based triggers (scan, summary, keep-alive)
  out/
    browser/     Playwright-based Zalando Lounge scraper and auth
    persistence/ Spring Data JDBC repositories and JDBC entities
    telegram/    Outbound Telegram notifications
config/          Spring configuration and @ConfigurationProperties
```

## Prerequisites

- Java 25
- Maven 3.9+
- PostgreSQL 14+
- A Telegram Bot token and group chat ID (see [BotFather](https://t.me/botfather))
- Docker + Docker Compose — the browser runs in the bundled `patchright` service (undetected Chromium) that the app connects to over the Playwright wire protocol

## Configuration

All sensitive values are read from environment variables. Copy the following and supply your values:

```bash
ZALANDO_EMAIL=your@email.ch
ZALANDO_PASSWORD=yourpassword
TELEGRAM_BOT_TOKEN=123456:ABC-your-token
TELEGRAM_GROUP_CHAT_ID=-1001234567890                # the ONLY chat the bot obeys; see Access control
MAX_KEEP_ALIVE_HOURS=2                            # optional, default 2
SESSION_FILE=session/state.json                   # optional, default shown
ZALANDO_BROWSER_WS_ENDPOINT=ws://localhost:3000/cartpilot  # Patchright server (dev default; compose uses ws://patchright:3000/cartpilot)
PLAYWRIGHT_HEADLESS=true                          # optional, default true (gates headed-login fallback; the Patchright server controls the real browser)
ZALANDO_HEADED_LOGIN_FALLBACK_ENABLED=false       # optional, default false
ZALANDO_HEADED_LOGIN_TIMEOUT_MS=240000            # optional, default 240000
ZALANDO_NETWORK_DIAGNOSTICS_ENABLED=false         # optional, default false
ZALANDO_SESSION_CHECK_TIMEOUT_MS=12000            # optional, default 12000
ZALANDO_LOGIN_NAVIGATION_TIMEOUT_MS=60000         # optional, default 60000
ZALANDO_LOGIN_POST_SUBMIT_TIMEOUT_MS=30000        # optional, default 30000
ZALANDO_AUTH_RETRY_BASE_DELAY_MS=1000             # optional, default 1000
ZALANDO_AUTH_CONTEXT_RESET_RETRIES=1              # optional, default 1
ZALANDO_TRUST_SESSION_FILE_IN_DEV=false           # optional, default false
ZALANDO_DIAGNOSTICS_DIR=diagnostics/auth          # optional, default shown
```

Telegram uses long polling and clears any existing webhook on startup, so no Telegram webhook URL is required.

The application also reads standard Spring Boot configuration from `application.yml`. Notable defaults:

| Property | Default | Description |
|---|---|---|
| `cartpilot.zalando.base-url` | `https://www.zalando-lounge.ch` | Lounge base URL |
| `cartpilot.zalando.retry-interval-seconds` | `60` | Retry interval between scan attempts |
| `cartpilot.zalando.retry-max-attempts` | `5` | Max retries per scan |
| `cartpilot.zalando.navigation-timeout-ms` | `60000` | Page navigation timeout |
| `cartpilot.zalando.element-timeout-ms` | `30000` | Timeout for locating an element (size toggles, add-to-cart button) |
| `cartpilot.zalando.login-max-attempts` | `3` | Login retries before failing |
| `cartpilot.zalando.browser-ws-endpoint` | `ws://patchright:3000/cartpilot` | WebSocket URL of the Patchright (undetected Chromium) browser server the app connects to |
| `cartpilot.zalando.headless` | `true` | Gates the headed-login fallback path; the real browser's headed/headless mode is set on the Patchright server (`PATCHRIGHT_HEADLESS`) |
| `cartpilot.zalando.headed-login-fallback-enabled` | `false` | Allow manual headed login fallback when scripted login fails |
| `cartpilot.zalando.headed-login-timeout-ms` | `240000` | Time window for manual fallback login |
| `cartpilot.zalando.network-diagnostics-enabled` | `false` | Log browser request/response metadata for auth troubleshooting |
| `cartpilot.zalando.session-check-timeout-ms` | `12000` | Timeout for lightweight session validity probe |
| `cartpilot.zalando.login-navigation-timeout-ms` | `60000` | Timeout for loading login page before filling credentials |
| `cartpilot.zalando.login-post-submit-timeout-ms` | `30000` | Timeout for post-submit redirect away from login |
| `cartpilot.zalando.auth-retry-base-delay-ms` | `1000` | Base delay used for exponential auth retry backoff with jitter |
| `cartpilot.zalando.auth-context-reset-retries` | `1` | Number of browser-context reset attempts after recoverable auth failures |
| `cartpilot.zalando.trust-session-file-in-dev` | `false` | In dev only, trust persisted session file when auth cookies exist |
| `cartpilot.zalando.diagnostics-dir` | `diagnostics/auth` | Folder where login failure diagnostics are written |
| `cartpilot.cart.expiry-minutes` | `20` | Cart item expiry window |
| `cartpilot.cart.keep-alive-interval-minutes` | `15` | Keep-alive check frequency |
| `cartpilot.scheduler.scan-cron` | `0 0 6 * * *` | Daily scan trigger (Europe/Zurich) |
| `cartpilot.scheduler.summary-cron` | `0 10 6 * * *` | Morning summary trigger |

## Running Locally

```bash
# Start PostgreSQL (example with Docker)
docker run -d --name cartpilot-db \
  -e POSTGRES_DB=cartpilot \
  -e POSTGRES_USER=cartpilot \
  -e POSTGRES_PASSWORD=secret \
  -p 5432:5432 postgres:16-alpine

# Start the Patchright browser server (undetected Chromium the app connects to)
docker compose up -d patchright

# Build and run (default profile is dev; dev uses H2 in-memory DB)
export ZALANDO_EMAIL=... ZALANDO_PASSWORD=... TELEGRAM_BOT_TOKEN=... \
  TELEGRAM_GROUP_CHAT_ID=... POSTGRES_PASSWORD=secret
mvn spring-boot:run
```

### First Login Bootstrap (when login automation is blocked)

The visible browser window is provided by the Patchright server, so run it headed first, then start the app with headed fallback enabled so you can log in manually and persist session state:

```bash
# Run the Patchright server headed so a real Chromium window appears (requires a display)
cd patchright && npm install && PATCHRIGHT_HEADLESS=false PATCHRIGHT_HOST=127.0.0.1 \
  PATCHRIGHT_PORT=3000 PATCHRIGHT_WS_PATH=cartpilot node server.js
```

```bash
export PLAYWRIGHT_HEADLESS=false
export ZALANDO_HEADED_LOGIN_FALLBACK_ENABLED=true
export ZALANDO_NETWORK_DIAGNOSTICS_ENABLED=true
mvn spring-boot:run
```

After a successful manual login, the storage state is saved to `SESSION_FILE` (default: `session/state.json`).
You can then switch back to headless mode for normal automation:

```bash
export PLAYWRIGHT_HEADLESS=true
export ZALANDO_HEADED_LOGIN_FALLBACK_ENABLED=false
```

### Manual Local Triggers

There are no dev-only HTTP command injection endpoints. For a manual scan, send `/scan` in the Telegram group as an admin, or temporarily schedule the next scan one minute in the future:

```bash
M=$(( ($(date +%M)+1)%60 ))
CARTPILOT_SCHEDULER_SCAN_CRON="0 $M * * * *" ./mvnw spring-boot:run
```

Use the environment variable for cron overrides. Passing a cron with spaces through `-Dspring-boot.run.arguments` splits the value.

## Building a Container Image

The application image is built with a [Paketo buildpack](https://paketo.io/) (no application `Dockerfile` needed). The browser runs in the separate `patchright` service ([patchright/Dockerfile](patchright/Dockerfile)). [docker-compose.yml](docker-compose.yml) expects the local image `zalando-lounge-cartpilot:0.1.0-SNAPSHOT` unless `CARTPILOT_IMAGE` is set.

CI publishes both images to Docker Hub: `patbaumgartner/zalando-lounge-cartpilot` (app) and `patbaumgartner/zalando-lounge-cartpilot-patchright` (browser). Every push to `main` updates the `:latest` tags; each `v*` release tag publishes `:<version>` plus `:latest`.

```bash
./mvnw spring-boot:build-image
docker compose up -d
```

Both published ports bind to `127.0.0.1` only. Port 3000 is the Patchright endpoint — anything that can reach it drives a browser holding the logged-in Zalando session, so it must not be exposed to the network; the app reaches it over the compose network instead. Port 8080 serves an unauthenticated actuator (`health`, `info`, details off) used by the container healthcheck.

To run a pushed image instead of the local buildpack image:

```bash
CARTPILOT_IMAGE=your-dockerhub-user/zalando-lounge-cartpilot:0.1.0 docker compose up -d
```

## Running Tests

```bash
mvn test
```

The test suite includes:

- **Unit tests** for all domain services, application services, and adapters
- **Integration test** (`ProfilePersistenceAdapterIntegrationTest`) against a real PostgreSQL via Testcontainers
- **Architecture tests** (`HexagonalArchitectureTest`) enforcing layer dependency rules with ArchUnit
- **Coding conventions** (`TaikaiTest`) enforcing naming, logging, import, and Spring conventions with Taikai
- **Browser adapter tests** (`CartApiTest`, `CampaignScraperTest`) — drive the basket and catalog JSON clients through rate limits, bot walls, logged-out HTML under HTTP 200 and malformed bodies via a scripted `PageHttpClient`, with no browser
- **Live cart test** (`PlaywrightBrowserAdapterLiveTest`) — opt-in smoke test that adds, keep-alive refreshes, and clears a real in-stock article against Zalando Lounge. Disabled by default; runs only with `-Dlive.cart.test=true` (requires a running Patchright server and a valid `session/state.json`).

## CI/CD & Security Pipelines

| Workflow | Trigger | Purpose |
|---|---|---|
| `CI` | push/pull_request on `main` | Build, run full Maven verification, and (on push to `main`) publish both `:latest` images to Docker Hub |
| `Release` | pushed tag `v*` | Build the app (Paketo) and Patchright images and push both to Docker Hub (`:<version>` and `:latest`) |
| `CodeQL` | push/pull_request on `main`, weekly cron | Static code security and quality analysis |
| `Dependency Review` | pull_request on `main` | Blocks PRs introducing high-severity vulnerable dependencies |
| `Dependabot` | weekly | Automated dependency update PRs for Maven, npm (`patchright`), and GitHub Actions |

## Telegram Commands

All commands work inside the group. Profile management is restricted to group admins.

| Command | Who | Description |
|---|---|---|
| `/status` | Anyone | Show cart items with expiry times and profile labels |
| `/links` | Anyone | Post a clickable link list for every open item today — reserved, blocked and notify-only |
| `/debug` | Anyone | Reservation counts by status, today's totals, and the live configuration |
| `/scan` | Admin | Trigger an immediate campaign scan |
| `/clear` | Admin | Remove all items currently in cart and mark active reservations as rejected |
| `/profiles` | Admin | List all profiles with active/inactive status |
| `/profile show {name}` | Admin | Show full profile details |
| `/profile activate {name}` | Admin | Activate a profile |
| `/profile deactivate {name}` | Admin | Deactivate a profile |
| `/profile set size {name} {category} {size}` | Admin | Update a size |
| `/profile set price {name} {category} {chf}` | Admin | Update a price cap |
| `/profile brand add {name} {tier1/tier2} {brand}` | Admin | Add a brand to tier 1 or 2 |
| `/profile brand remove {name} {brand}` | Admin | Remove a brand |
| `/help` | Anyone | List all commands |

## Member Profiles

Profiles are stored in the database and represent the people shopping in the group.

| Field | Description |
|---|---|
| `name` | Display name (e.g. `Pat`, `Wife`, `Son`) |
| `gender` | `MEN`, `WOMEN`, `KIDS`, or `UNISEX` |
| `sizes` | Per-category map: `shoes`, `shirts`, `trousers`, `jackets`, `underwear`, `swimwear`, `jeans`, `belts` |
| `brand_tier_1` | Top-priority brands → auto-reserve |
| `brand_tier_2` | Mid-priority brands → notify only |
| `brand_aliases` | Local alias overrides per profile, e.g. `TNF=The North Face` |
| `max_price_shoes` | CHF cap for shoes |
| `max_price_jackets` | CHF cap for jackets / outerwear |
| `max_price_clothing` | CHF cap for all other clothing |
| `active` | Inactive profiles are skipped during scans |

### Scoring

| Condition | Points |
|---|---|
| Brand in Tier 1 | 50 |
| Brand in Tier 2 | 30 |
| Discount ≥ 60 % | +20 |
| Discount 40–59 % | +10 |

A Tier 1 match triggers **AUTO_RESERVE** (cart + immediate notification). A Tier 2 match triggers **NOTIFY_ONLY** (included in morning summary). No match → silently skipped.

Brand matching tolerates spelling variants, but the fuzzy tolerance scales with name length: names under 5 characters must match exactly. A flat two-edit tolerance makes `Nike`/`Nine`, `Gap`/`Gas` and `Lowa`/`Lowe` equal, and on a Tier 1 list that silently reserves the wrong article. Longer names still absorb real typos (`Mamut` → `Mammut`), and the word-subset rule still matches `Levi` inside `Levi's` or `Salomon` inside `Salomon S-Lab`.

An article the profile has already bought is skipped on later scans. This is matched on the article sku in the product URL, not the database row, because every scan re-inserts its products with fresh ids.

### Reservation statuses

| Status | Meaning |
|---|---|
| `PENDING` | Matched, notify-only — never went to the basket |
| `IN_CART` | Held in the basket, kept alive every 15 min |
| `PURCHASE_INITIATED` | Someone tapped Buy; further Buy/Skip taps are ignored |
| `REJECTED` | Skipped, or released by `/clear` or a pre-dispatch clear |
| `EXPIRED` | The hold ran out or could not be renewed |
| `OUT_OF_STOCK` | The size was no longer purchasable |
| `BLOCKED` | Bot protection refused the basket call — still buyable by hand |
| `FAILED` | The basket call failed for a reason that says nothing about stock |

## Tech Stack

| Layer | Technology |
|---|---|
| Runtime | Java 25, virtual threads |
| Framework | Spring Boot 4.1.0 |
| Persistence | Spring Data JDBC + Flyway + PostgreSQL |
| Browser automation | Playwright Java client 1.61.0 + Patchright Chromium sidecar |
| Telegram | TelegramBots 10.2.0 (long polling) |
| Container image | Paketo Buildpack (`paketobuildpacks/builder-jammy-base`) |
| Architecture tests | ArchUnit 1.5.0, Taikai 1.64.0 |
| Test database | H2 (unit/integration), PostgreSQL via Testcontainers (integration) |
