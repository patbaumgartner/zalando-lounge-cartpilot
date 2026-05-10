-- ═══════════════════════════════════════════════════════════════
-- CartPilot – Initial Schema
-- ═══════════════════════════════════════════════════════════════

-- ── Family member profiles ────────────────────────────────────
CREATE TABLE profiles (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name                 VARCHAR(100)   NOT NULL UNIQUE,
    gender               VARCHAR(10)    NOT NULL,
    active               BOOLEAN        NOT NULL DEFAULT TRUE,
    max_price_shoes      NUMERIC(10, 2),
    max_price_jackets    NUMERIC(10, 2),
    max_price_clothing   NUMERIC(10, 2),
    brand_tier1          TEXT,
    brand_tier2          TEXT,
    brand_aliases        TEXT,
    created_at           TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ── Sizes per profile (one row per category) ─────────────────
CREATE TABLE profile_sizes (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    profile_id  BIGINT      NOT NULL REFERENCES profiles (id) ON DELETE CASCADE,
    category    VARCHAR(20) NOT NULL,
    size        VARCHAR(50) NOT NULL,
    UNIQUE (profile_id, category)
);

-- ── Campaign products discovered each morning ─────────────────
CREATE TABLE discovered_products (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    campaign_id     VARCHAR(255)   NOT NULL,
    brand           VARCHAR(255)   NOT NULL,
    name            VARCHAR(500)   NOT NULL,
    category        VARCHAR(20)    NOT NULL,
    gender          VARCHAR(10)    NOT NULL,
    sizes_available TEXT,
    original_price  NUMERIC(10, 2) NOT NULL,
    lounge_price    NUMERIC(10, 2) NOT NULL,
    discount_pct    INTEGER        NOT NULL,
    product_url     TEXT           NOT NULL,
    status          VARCHAR(20)    NOT NULL DEFAULT 'DISCOVERED',
    discovered_at   TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ── Per-profile reservation decisions ────────────────────────
CREATE TABLE product_reservations (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    product_id       BIGINT      NOT NULL REFERENCES discovered_products (id),
    profile_id       BIGINT      NOT NULL REFERENCES profiles (id),
    size             VARCHAR(50),
    decision         VARCHAR(20) NOT NULL,
    status           VARCHAR(30) NOT NULL,
    score            INTEGER     NOT NULL DEFAULT 0,
    telegram_msg_id  INTEGER,
    cart_added_at    TIMESTAMP,
    cart_expires_at  TIMESTAMP,
    created_at       TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (product_id, profile_id)
);

-- ── Items purchased via the Buy button ───────────────────────
CREATE TABLE purchased_items (
    id                     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    profile_id             BIGINT       NOT NULL REFERENCES profiles (id),
    product_id             BIGINT       NOT NULL REFERENCES discovered_products (id),
    purchased_at           TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    purchased_by_username  VARCHAR(100)
);

-- ── Known brand catalogue ─────────────────────────────────────
CREATE TABLE known_brands (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    brand_name    VARCHAR(255) NOT NULL UNIQUE,
    first_seen_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ── Seed: Pat's profile ───────────────────────────────────────
INSERT INTO profiles (name, gender, active,
                      max_price_shoes, max_price_jackets, max_price_clothing,
                      brand_tier1, brand_tier2, brand_aliases)
VALUES ('Pat', 'MEN', TRUE, 250.00, 400.00, 200.00,
        'Mammut,Arc''teryx,Patagonia,ECCO,Salomon,Scarpa,Fjällräven',
        'Jack Wolfskin,Columbia,Haglöfs,Icebreaker,Merrell,Salewa,Helly Hansen,Smartwool,Vaude,Bergans of Norway',
        'TNF=The North Face');

INSERT INTO profile_sizes (profile_id, category, size)
SELECT id, 'SHOES',     '43'    FROM profiles WHERE name = 'Pat' UNION ALL
SELECT id, 'SHIRTS',    'L'     FROM profiles WHERE name = 'Pat' UNION ALL
SELECT id, 'TROUSERS',  '50'    FROM profiles WHERE name = 'Pat' UNION ALL
SELECT id, 'JACKETS',   '52'    FROM profiles WHERE name = 'Pat' UNION ALL
SELECT id, 'UNDERWEAR', 'L'     FROM profiles WHERE name = 'Pat' UNION ALL
SELECT id, 'SWIMWEAR',  'M'     FROM profiles WHERE name = 'Pat' UNION ALL
SELECT id, 'JEANS',     '34/32' FROM profiles WHERE name = 'Pat';
