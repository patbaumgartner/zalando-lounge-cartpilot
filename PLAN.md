# CartPilot – App Plan

## Overview

CartPilot is a headless Spring Boot backend that monitors Zalando Lounge CH every morning at 06:00, automatically adds matching items to cart for configured **family member profiles**, and posts notifications to a shared **Telegram group**. Any group member can approve or skip a purchase directly from the chat.

No web UI. No admin panel. Everything controlled via Telegram.

---

## Telegram Group Model

CartPilot lives inside a **Telegram group** shared by all family members.

- All notifications and summaries appear in the group — everyone sees them.
- **Any group member** can tap **[🛍 Buy]** or **[❌ Skip]** on any notification.
- When someone acts, the bot updates the message and names them: `✅ @pat bought Mammut Jacket`.
- Profile management commands are restricted to **group admins** only.
- The bot ignores messages from other bots.

No private chat. No per-user conversation state. Everything happens in the group, transparently.

---

## Member Profiles

Profiles are stored in the database and managed by group admins via simple one-liner Telegram commands.

| Field | Description |
|-------|-------------|
| `name` | Display name (e.g. `Pat`, `Wife`, `Son`) |
| `gender` | `MEN`, `WOMEN`, `KIDS`, or `UNISEX` |
| `sizes` | Per-category map: `shoes`, `shirts`, `trousers`, `jackets`, `underwear`, `swimwear`, `jeans` |
| `brand_tier_1` | Top-priority brands → auto-reserve |
| `brand_tier_2` | Mid-priority brands → notify only |
| `brand_aliases` | Local alias overrides, e.g. `TNF=The North Face` |
| `max_price_shoes` | CHF cap for shoes |
| `max_price_jackets` | CHF cap for jackets / outerwear |
| `max_price_clothing` | CHF cap for all other clothing |
| `active` | Inactive profiles are skipped |

### Scoring (simple)

| Condition | Points |
|-----------|--------|
| Brand in Tier 1 | 50 |
| Brand in Tier 2 | 30 |
| Discount ≥ 60% | +20 |
| Discount 40–59% | +10 |

| Result | Action |
|--------|--------|
| Tier 1 brand match | **AUTO_RESERVE** → add to cart + immediate group notification |
| Tier 2 brand match | **NOTIFY_ONLY** → included in 06:10 morning summary |
| No brand match | Silently skipped |

### Hard gates (any failure = skip for this profile)

1. Campaign gender matches profile gender (or product is `UNISEX`).
2. Profile's size for the product category is available.
3. Lounge price is under the profile's cap for this category.
4. Item was not already purchased by this profile (tracked from previous Buy actions).

### Initial seed data (Pat)

| Field | Value |
|-------|-------|
| gender | MEN |
| shoes | 43 EU |
| shirts | L/M |
| trousers | EU 50 |
| jackets | EU 50–52 |
| underwear | L |
| swimwear | M–L |
| jeans | 34/32 (W/L) |
| brand_tier_1 | Mammut, Arc'teryx, Patagonia, ECCO, Salomon, Scarpa, Fjällräven |
| brand_tier_2 | Jack Wolfskin, Columbia, Haglöfs, Icebreaker, Merrell, Salewa, Helly Hansen, Smartwool, Vaude, Bergans of Norway |

---

## Architecture

```
┌─────────────────────────────────────────────────────┐
│                  Spring Boot App                    │
│                                                     │
│  Scheduler (06:00 Europe/Zurich)                    │
│       │                                             │
│       ▼                                             │
│  CampaignScannerService                             │
│       │  uses                                       │
│       ▼                                             │
│  PlaywrightBrowserService ──► Zalando Lounge CH     │
│       │                                             │
│       ├──► AuthService          (session mgmt)      │
│       │                                             │
│       ▼                                             │
│  ProductFilterService  (score per product × profile)│
│       │                                             │
│       ▼                                             │
│  CartService           (add to cart per profile)    │
│       │                                             │
│       ├──► CartKeepAliveService (15-min pings)      │
│       │                                             │
│       ▼                                             │
│  TelegramGroupService  (post to group)              │
│       │                                             │
│       ▼                                             │
│  TelegramBotHandler    (buttons / commands)         │
└─────────────────────────────────────────────────────┘
```

**Tech stack:**
- Java 21 LTS + Spring Boot 4.1.0
- Maven
- Playwright Java client connecting to a Patchright Chromium sidecar
- TelegramBots 10.0.0 (long polling)
- Spring Scheduler (`@Scheduled`)
- Spring Data JDBC + Flyway — PostgreSQL (prod) / H2 (dev / tests)
- Docker Compose (app image, Patchright sidecar, PostgreSQL)

---

## Use Cases

### UC-01 · Daily Campaign Scan

**Trigger:** `0 0 6 * * *` (06:00 Europe/Zurich)

1. Playwright opens `https://www.zalando-lounge.ch/event`.
2. Auth check — re-login if session expired (UC-06).
3. Extract `window.__INITIAL_STATE__.mylounge.openCampaigns` from SSR HTML.
4. Keep only campaigns where `startsAt` date = today in `Europe/Zurich`.
5. If none found: retry every 60 s for up to 5 min (CDN propagation window).
6. For each new campaign: scrape product listing — brand, name, category, gender, available sizes, original price, lounge price, discount %, product URL.
7. Persist each product in `discovered_products` with status `DISCOVERED`.
8. Pass product list to `ProductFilterService` (UC-02).

**Errors:**
- Login failed → group message: `🚨 Login failed. CartPilot stopped.`
- No campaigns after 5-min retry → group message: `ℹ️ No new campaigns today.`

---

### UC-02 · Filter & Score

**For each discovered product × active profile:**

1. Apply hard gates (gender, size available, price cap, not already purchased). Any failure → skip silently for this profile.
2. Resolve brand against this profile's tiers using fuzzy matching (Levenshtein ≤ 2). No match → skip silently.
3. Compute score: brand tier weight + discount bonus.
4. Tier 1 match → **AUTO_RESERVE** → UC-03.
5. Tier 2 match → **NOTIFY_ONLY** → include in 06:10 summary (UC-04).

A single product can produce multiple reservations (one per matching profile).

---

### UC-03 · Add to Cart

**For each AUTO_RESERVE product-profile pair:**

1. Playwright opens the product page in a new browser tab.
2. Selects the profile's size for this category.
3. Clicks "Add to cart". Confirms via cart badge count in DOM.
4. Records `cartAddedAt`, `cartExpiresAt` = now + 20 min, status = `IN_CART`.
5. Posts immediate group notification (UC-04).
6. Starts keep-alive task (UC-05).

If the profile's size is out of stock → status `OUT_OF_STOCK`, no notification for this profile.

---

### UC-04 · Telegram Group Notification

#### Per-item message (immediate, on AUTO_RESERVE)

```
🛒 Reserved for Pat

🏷 Mammut Convey Tour 45+10
📂 Backpack  ·  📐 Size 43  ·  💰 CHF 189 (was CHF 310, −39%)
⏳ Cart expires in ~20 min (auto-renewed up to 2 h)

[🛍 Buy]  [❌ Skip]  [🔗 View]
```

- **[🛍 Buy]** → sends checkout deep-link to the group; marks `PURCHASE_INITIATED`; stops keep-alive.
- **[❌ Skip]** → Playwright removes item from cart; marks `REJECTED`; bot edits message to show who acted.
- **[🔗 View]** → posts product URL.

After any button tap, the bot edits the original message to reflect the outcome and actor:
```
✅ @pat bought Mammut Convey Tour — CHF 189
```

#### Morning summary (06:10 AM, always sent)

```
📋 CartPilot – 10 May 2026

✅ Auto-reserved (2 items):
  • Pat   | Mammut Jacket – CHF 189 (Tier 1)  ⏳ expires 08:30
  • Wife  | Arc'teryx Shell – CHF 340 (Tier 1) ⏳ expires 08:32

👀 Review manually (1 item):
  • Pat   | Jack Wolfskin Fleece – CHF 79 (Tier 2)  🔗 link

📭 New campaigns today: Yes (3)
```

The summary is always sent — even when zero items matched — to confirm the bot ran.

---

### UC-05 · Cart Keep-Alive

Every 15 min, for each item with status `IN_CART`:

1. Playwright reloads the cart page. Verifies item is still present.
2. If gone (expired or bought outside the app) → mark `EXPIRED`; post to group: `⚠️ [Item] for [Profile] expired — no action taken.`
3. Reset `cartExpiresAt` = now + 20 min.
4. Stop automatically after `MAX_KEEP_ALIVE_HOURS` (default 2 h) or when a user acts.

---

### UC-06 · Session Management

1. On first run: Playwright logs in with credentials from env vars; saves browser storage state to `session/state.json`.
2. Every subsequent run: loads `session/state.json` (cookies + local storage) before navigating.
3. Before each scan: validates session by navigating to a protected page; re-logs in and overwrites `session/state.json` if invalid.
4. Credentials are **never** stored in code — always read from environment variables.

---

### UC-07 · Telegram Commands

All commands work in the group. Profile management is restricted to **group admins**.

| Command | Who | Description |
|---------|-----|-------------|
| `/status` | Anyone | Show cart items with expiry times and profile labels |
| `/scan` | Admin | Trigger an immediate campaign scan |
| `/clear` | Admin | Remove all items currently in cart and mark active reservations as rejected |
| `/profiles` | Admin | List all profiles with active/inactive status |
| `/profile show <name>` | Admin | Show full profile details |
| `/profile activate <name>` | Admin | Activate a profile |
| `/profile deactivate <name>` | Admin | Deactivate a profile |
| `/profile set size <name> <category> <size>` | Admin | Update a size |
| `/profile set price <name> <category> <chf>` | Admin | Update a price cap |
| `/profile brand add <name> <tier> <brand>` | Admin | Add a brand to tier1 or tier2 |
| `/profile brand remove <name> <brand>` | Admin | Remove a brand |
| `/help` | Anyone | List all commands |

**Validation:** Invalid inputs are rejected inline with the expected format. No multi-step wizards — every command is a single one-liner.

**Brand fuzzy matching:** When adding a brand, Levenshtein distance ≤ 2 triggers a one-shot inline keyboard confirmation: `Did you mean Arc'teryx? [✅ Yes] [✏️ Re-enter]`. This is the only two-step interaction.

---

## Database Schema

```sql
-- Family member profiles
profiles (
  id, name, gender, active,
  max_price_shoes, max_price_jackets, max_price_clothing,
  brand_tier_1,   -- comma-separated
  brand_tier_2,   -- comma-separated
  brand_aliases,  -- comma-separated key=value pairs
  created_at
)

-- Sizes per profile (one row per category)
profile_sizes (profile_id, category, size)

-- Campaign products discovered each morning
discovered_products (
  id, campaign_id, brand, name, category, gender,
  sizes_available, original_price, lounge_price, discount_pct,
  product_url, status, discovered_at
)

-- Per-profile reservation decisions
product_reservations (
  id, product_id, profile_id, size, decision,
  status, score, cart_added_at, cart_expires_at
)

-- Items purchased via Buy button (suppresses future recommendations)
purchased_items (id, profile_id, product_id, purchased_at, purchased_by_username)

-- Known brand catalog (populated from discovered_products on every scan)
known_brands (id, brand_name, first_seen_at)
```

---

## Campaign Timing

Campaigns start at **04:00 UTC = 06:00 CEST** (Europe/Zurich in summer, UTC+2). The scheduler always uses the `Europe/Zurich` timezone — never a hardcoded UTC offset. Campaign data is embedded in the SSR HTML as `window.__INITIAL_STATE__.mylounge.openCampaigns`. A 5-minute retry window handles CDN propagation delays.

---

## Environment Variables

```
ZALANDO_EMAIL=
ZALANDO_PASSWORD=
TELEGRAM_BOT_TOKEN=
TELEGRAM_GROUP_CHAT_ID=
SESSION_FILE=session/state.json
ZALANDO_BROWSER_WS_ENDPOINT=ws://localhost:3000/cartpilot
MAX_KEEP_ALIVE_HOURS=2
```
