-- ═══════════════════════════════════════════════════════════════
-- CartPilot – V3: indexes for the queries the app actually runs
-- ═══════════════════════════════════════════════════════════════
-- Every hot read was a full table scan, and none of these tables is
-- ever pruned: reservations and discovered products accumulate with
-- every daily scan, so the scans get slower for the rest of the
-- deployment's life.
--
--   product_reservations(status)      – /status, /links, /debug, the
--                                       keep-alive cycle and the morning
--                                       summary all filter by status.
--   product_reservations(created_at)  – /links restricts to today.
--   discovered_products(discovered_at)– /debug and the morning summary
--                                       count today's products.
--   purchased_items(profile_id)       – the already-purchased gate runs
--                                       once per active profile per scan.

CREATE INDEX IF NOT EXISTS idx_product_reservations_status
    ON product_reservations (status);

CREATE INDEX IF NOT EXISTS idx_product_reservations_created_at
    ON product_reservations (created_at);

CREATE INDEX IF NOT EXISTS idx_discovered_products_discovered_at
    ON discovered_products (discovered_at);

CREATE INDEX IF NOT EXISTS idx_purchased_items_profile_id
    ON purchased_items (profile_id);

-- The Buy button is idempotent at the domain level, but nothing stopped
-- two taps from recording the same purchase twice.
CREATE UNIQUE INDEX IF NOT EXISTS uq_purchased_items_profile_product
    ON purchased_items (profile_id, product_id);
