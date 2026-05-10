package com.patbaumgartner.zalando.lounge.cartpilot.domain.service;

import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.BrandTier;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.Decision;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.DiscoveredProduct;

/**
 * Computes a numeric score for a product given a matched brand tier.
 *
 * Scoring table (from specification): Brand Tier 1 → 50 pts Brand Tier 2 → 30 pts
 * Discount ≥ 60 % → +20 pts Discount 40–59 % → +10 pts
 *
 * Decision table: Tier 1 match → AUTO_RESERVE Tier 2 match → NOTIFY_ONLY
 */
public class ProductScorer {

	private static final int HIGH_DISCOUNT_THRESHOLD = 60;

	private static final int MEDIUM_DISCOUNT_THRESHOLD = 40;

	private static final int HIGH_DISCOUNT_BONUS = 20;

	private static final int MEDIUM_DISCOUNT_BONUS = 10;

	public record ScoredDecision(int score, Decision decision) {
	}

	public ScoredDecision score(DiscoveredProduct product, BrandTier tier) {
		int points = tier.baseScore() + discountBonus(product.discountPct());
		Decision decision = tier == BrandTier.TIER_1 ? Decision.AUTO_RESERVE : Decision.NOTIFY_ONLY;
		return new ScoredDecision(points, decision);
	}

	private int discountBonus(int discountPct) {
		if (discountPct >= HIGH_DISCOUNT_THRESHOLD) {
			return HIGH_DISCOUNT_BONUS;
		}
		if (discountPct >= MEDIUM_DISCOUNT_THRESHOLD) {
			return MEDIUM_DISCOUNT_BONUS;
		}
		return 0;
	}

}
