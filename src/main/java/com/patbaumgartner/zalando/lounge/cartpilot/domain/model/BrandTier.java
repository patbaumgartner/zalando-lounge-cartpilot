package com.patbaumgartner.zalando.lounge.cartpilot.domain.model;

public enum BrandTier {

	TIER_1(50), TIER_2(30);

	private final int baseScore;

	BrandTier(int baseScore) {
		this.baseScore = baseScore;
	}

	public int baseScore() {
		return baseScore;
	}

}
