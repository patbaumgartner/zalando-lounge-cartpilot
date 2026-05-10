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
BRAND_ALIASES=TNF=The North Face,ARC=Arc'teryx   # optional, comma-separated
MAX_KEEP_ALIVE_HOURS=2                            # optional, default 2
SESSION_FILE=session/state.json                   # optional, default shown
```

The application also reads standard Spring Boot configuration from `application.yml`. Notable defaults:

| Property | Default | Description |
|---|---|---|
| `cartpilot.zalando.base-url` | `https://www.zalando-lounge.ch` | Lounge base URL |
| `cartpilot.zalando.retry-interval-seconds` | `60` | Retry interval between scan attempts |
| `cartpilot.zalando.retry-max-attempts` | `5` | Max retries per scan |
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

# Build and run (dev profile uses H2 in-memory DB for tests)
export ZALANDO_EMAIL=... ZALANDO_PASSWORD=... TELEGRAM_BOT_TOKEN=... \
       TELEGRAM_GROUP_CHAT_ID=... TELEGRAM_WEBHOOK_URL=... POSTGRES_PASSWORD=secret
mvn spring-boot:run
```

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

The test suite (108 tests) includes:

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
| Telegram | TelegramBots 9.6.0 (webhook mode) |
| Container image | Paketo Buildpack (`paketobuildpacks/builder-jammy-base`) |
| Architecture tests | ArchUnit 1.4.2, Taikai 1.63.0 |
| Test database | H2 (unit/integration), PostgreSQL via Testcontainers (integration) |
