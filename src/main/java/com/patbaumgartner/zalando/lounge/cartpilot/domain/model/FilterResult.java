package com.patbaumgartner.zalando.lounge.cartpilot.domain.model;

/**
 * The outcome of scoring one product against one profile. Carries everything needed for
 * the downstream reservation decision.
 */
public record FilterResult(DiscoveredProduct product, Profile profile, String size, Decision decision,
		BrandTier brandTier, int score) {
}
