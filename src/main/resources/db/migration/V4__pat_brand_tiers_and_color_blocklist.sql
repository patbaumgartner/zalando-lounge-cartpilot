-- ═══════════════════════════════════════════════════════════════
-- CartPilot – V4: restrict Pat's brand tiers + color blocklist
-- ═══════════════════════════════════════════════════════════════
-- 1. Pat's Tier-1 list is reduced to the five brands he is happy with
--    (ECCO, Marc O'Polo, Haglöfs, Mammut, Geox). The leftovers of the old
--    Tier-1 list (TNF, Arc'teryx, Patagonia, Vaude, Scarpa, Dolomite,
--    Patrick) move to Tier 2 (notify-only); the old Tier-2 list is dropped.
-- 2. discovered_products gains a `color` column so the scanner can record
--    the article color reported by the catalog API.
-- 3. profiles gains a `blocked_colors` column: a comma-separated list of
--    color names (accent-folded, case-insensitive substring match, e.g.
--    'neon,pink,lila') whose products are silently skipped for that
--    profile. Left empty for Pat — extend via SQL as ugly colors show up:
--      UPDATE profiles SET blocked_colors = 'neon,pink,lila' WHERE name = 'Pat';

ALTER TABLE discovered_products ADD COLUMN IF NOT EXISTS color VARCHAR(100);

ALTER TABLE profiles ADD COLUMN IF NOT EXISTS blocked_colors TEXT;

UPDATE profiles
SET brand_tier1 = 'ECCO,Marc O''Polo,Haglöfs,Mammut,Geox',
    brand_tier2 = 'TNF,Arc''teryx,Patagonia,Vaude,Scarpa,Dolomite,Patrick'
WHERE name = 'Pat';
