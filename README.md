# Zalando Lounge CartPilot

[![CI](https://github.com/patbaumgartner/zalando-lounge-cartpilot/actions/workflows/ci.yml/badge.svg)](https://github.com/patbaumgartner/zalando-lounge-cartpilot/actions/workflows/ci.yml)
[![Release](https://github.com/patbaumgartner/zalando-lounge-cartpilot/actions/workflows/release.yml/badge.svg)](https://github.com/patbaumgartner/zalando-lounge-cartpilot/actions/workflows/release.yml)
[![CodeQL](https://github.com/patbaumgartner/zalando-lounge-cartpilot/actions/workflows/codeql.yml/badge.svg)](https://github.com/patbaumgartner/zalando-lounge-cartpilot/actions/workflows/codeql.yml)
[![Dependency Review](https://github.com/patbaumgartner/zalando-lounge-cartpilot/actions/workflows/dependency-review.yml/badge.svg)](https://github.com/patbaumgartner/zalando-lounge-cartpilot/actions/workflows/dependency-review.yml)
[![Dependabot](https://img.shields.io/badge/dependabot-enabled-025E8C?logo=dependabot)](https://github.com/patbaumgartner/zalando-lounge-cartpilot/security/dependabot)

![Java](https://img.shields.io/badge/Java-21-007396?logo=openjdk)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0.6-6DB33F?logo=springboot)
![Maven](https://img.shields.io/badge/build-Maven-C71A36?logo=apachemaven)
![PostgreSQL](https://img.shields.io/badge/database-PostgreSQL-4169E1?logo=postgresql)
![Playwright](https://img.shields.io/badge/browser-Playwright-2EAD33?logo=playwright)
![Docker Hub](https://img.shields.io/badge/publish-Docker%20Hub-2496ED?logo=docker)

Headless Spring Boot backend that monitors [Zalando Lounge CH](https://www.zalando-lounge.ch) every morning, automatically adds matching items to cart for configured family member profiles, and posts notifications to a shared Telegram group.

No web UI. No admin panel. Everything controlled via Telegram.

## How It Works

1. **06:00 CEST** — The scheduler scrapes active campaigns from Zalando Lounge CH using Playwright (headless Chromium).
2. Products are matched against each active profile's brand tiers, gender, sizes, and price caps.
3. **Tier 1 brands** → item is added to cart automatically and a notification is posted to the group.
4. **Tier 2 brands** → collected into a **06:10 morning summary** posted to the group.
5. Group members tap **[🛍 Buy]** or **[❌ Skip]** inline buttons to act on notifications.
6. Every 15 minutes the cart keep-alive scheduler re-checks reserved items and resets their expiry (default 20 min timeout).

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

- Java 21
- Maven 3.9+
- PostgreSQL 14+
- A Telegram Bot token and group chat ID (see [BotFather](https://t.me/botfather))
- Playwright Chromium system dependencies (handled automatically by the Paketo buildpack when using `mvn spring-boot:build-image`)

## Configuration

All sensitive values are read from environment variables. Copy the following and supply your values:

```bash
ZALANDO_EMAIL=your@email.ch
ZALANDO_PASSWORD=yourpassword
TELEGRAM_BOT_TOKEN=123456:ABC-your-token
TELEGRAM_GROUP_CHAT_ID=-1001234567890
TELEGRAM_WEBHOOK_URL=https://your-host/telegram/webhook
BRAND_ALIASES=TNF=The North Face,ARC=Arc'teryx    # optional, comma-separated
MAX_KEEP_ALIVE_HOURS=2                            # optional, default 2
SESSION_FILE=session/state.json                   # optional, default shown
PLAYWRIGHT_HEADLESS=true                          # optional, default true
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

The application also reads standard Spring Boot configuration from `application.yml`. Notable defaults:

| Property | Default | Description |
|---|---|---|
| `cartpilot.zalando.base-url` | `https://www.zalando-lounge.ch` | Lounge base URL |
| `cartpilot.zalando.retry-interval-seconds` | `60` | Retry interval between scan attempts |
| `cartpilot.zalando.retry-max-attempts` | `5` | Max retries per scan |
| `cartpilot.zalando.navigation-timeout-ms` | `60000` | Page navigation timeout |
| `cartpilot.zalando.login-max-attempts` | `3` | Login retries before failing |
| `cartpilot.zalando.headless` | `true` | Run Chromium in headless mode |
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

# Build and run (default profile is dev; dev uses H2 in-memory DB)
export ZALANDO_EMAIL=... ZALANDO_PASSWORD=... TELEGRAM_BOT_TOKEN=... \
       TELEGRAM_GROUP_CHAT_ID=... TELEGRAM_WEBHOOK_URL=... POSTGRES_PASSWORD=secret
mvn spring-boot:run
```

### First Login Bootstrap (when login automation is blocked)

If your local machine can show a browser window, run once with headed fallback enabled so you can log in manually and persist session state:

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

### Dev Command Injection (No Telegram Transport)

When running with the `dev` profile, you can trigger command handling directly:

```bash
curl -X POST http://localhost:8080/dev/telegram/command \
  -H 'Content-Type: application/json' \
  -d '{"text":"/scan","asAdmin":true}'
```

This is useful for reproducible local debugging without relying on Telegram delivery.

You can also trigger browser authentication bootstrap directly (without running a full scan):

```bash
curl -X POST http://localhost:8080/dev/browser/auth/bootstrap
```

The endpoint returns whether authentication succeeded and whether the configured session file exists.
On failure, it also includes `failureCategory` and `diagnosticsPath` (if diagnostics are enabled).

## Building a Container Image

The project uses a [Paketo buildpack](https://paketo.io/) with a custom layer that installs the Chromium system libraries required by Playwright. No `Dockerfile` needed.

```bash
mvn spring-boot:build-image
docker run -e ZALANDO_EMAIL=... -e ZALANDO_PASSWORD=... \
           -e TELEGRAM_BOT_TOKEN=... -e TELEGRAM_GROUP_CHAT_ID=... \
           -e TELEGRAM_WEBHOOK_URL=... -e POSTGRES_PASSWORD=... \
           zalando-lounge-cartpilot:0.1.0-SNAPSHOT
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

## CI/CD & Security Pipelines

| Workflow | Trigger | Purpose |
|---|---|---|
| `CI` | push/pull_request on `main` | Build and run full Maven verification |
| `Release` | pushed tag `v*` | Build image with Paketo and push to Docker Hub |
| `CodeQL` | push/pull_request on `main`, weekly cron | Static code security and quality analysis |
| `Dependency Review` | pull_request on `main` | Blocks PRs introducing high-severity vulnerable dependencies |
| `Dependabot` | weekly | Automated dependency update PRs for Maven and GitHub Actions |

## Telegram Commands

All commands work inside the group. Profile management is restricted to group admins.

| Command | Who | Description |
|---|---|---|
| `/status` | Anyone | Show cart items with expiry times and profile labels |
| `/scan` | Admin | Trigger an immediate campaign scan |
| `/clear` | Admin | Remove all items currently in cart and mark active reservations as rejected |
| `/profiles` | Admin | List all profiles with active/inactive status |
| `/profile show <name>` | Admin | Show full profile details |
| `/profile activate <name>` | Admin | Activate a profile |
| `/profile deactivate <name>` | Admin | Deactivate a profile |
| `/profile set size <name> <category> <size>` | Admin | Update a size |
| `/profile set price <name> <category> <chf>` | Admin | Update a price cap |
| `/profile brand add <name> <tier> <brand>` | Admin | Add a brand to tier 1 or 2 |
| `/profile brand remove <name> <brand>` | Admin | Remove a brand |
| `/help` | Anyone | List all commands |

## Member Profiles

Profiles are stored in the database and represent the people shopping in the group.

| Field | Description |
|---|---|
| `name` | Display name (e.g. `Pat`, `Wife`, `Son`) |
| `gender` | `MEN`, `WOMEN`, `KIDS`, or `UNISEX` |
| `sizes` | Per-category map: `shoes`, `shirts`, `trousers`, `jackets`, `underwear`, `swimwear`, `jeans` |
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

## Tech Stack

| Layer | Technology |
|---|---|
| Runtime | Java 21, virtual threads |
| Framework | Spring Boot 4.0.6 |
| Persistence | Spring Data JDBC + Flyway + PostgreSQL |
| Browser automation | Microsoft Playwright (Chromium) |
| Telegram | TelegramBots 9.6.0 (long polling) |
| Container image | Paketo Buildpack (`paketobuildpacks/builder-jammy-base`) |
| Architecture tests | ArchUnit 1.4.2, Taikai 1.63.0 |
| Test database | H2 (unit/integration), PostgreSQL via Testcontainers (integration) |
