-- ═══════════════════════════════════════════════════════════════
-- CartPilot – V2: adjust Pat's Tier-1 brand list
-- ═══════════════════════════════════════════════════════════════
-- Adds "Patrick" as a Tier-1 (auto-reserve) brand for Pat and makes
-- sure "Mammut" is present, without disturbing the rest of the list.
-- Both updates are idempotent: the guard skips a brand that is already
-- in the comma-separated Tier-1 list, so re-running is a no-op.
-- Brand tiers apply across all categories; Patrick shoes are picked up
-- because Pat already has a SHOES size (43) and a shoes price cap.

UPDATE profiles
SET brand_tier1 = CASE
        WHEN brand_tier1 IS NULL OR brand_tier1 = '' THEN 'Patrick'
        ELSE brand_tier1 || ',Patrick'
    END
WHERE name = 'Pat'
  AND (',' || COALESCE(brand_tier1, '') || ',') NOT LIKE '%,Patrick,%';

UPDATE profiles
SET brand_tier1 = CASE
        WHEN brand_tier1 IS NULL OR brand_tier1 = '' THEN 'Mammut'
        ELSE brand_tier1 || ',Mammut'
    END
WHERE name = 'Pat'
  AND (',' || COALESCE(brand_tier1, '') || ',') NOT LIKE '%,Mammut,%';
