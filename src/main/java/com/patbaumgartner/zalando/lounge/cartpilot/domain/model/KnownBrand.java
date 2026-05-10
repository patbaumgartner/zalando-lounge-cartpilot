package com.patbaumgartner.zalando.lounge.cartpilot.domain.model;

import java.time.LocalDateTime;

/** A brand discovered from any campaign — used for fuzzy-match suggestions. */
public record KnownBrand(Long id, String brandName, LocalDateTime firstSeenAt) {

	public static KnownBrand of(String brandName) {
		return new KnownBrand(null, brandName, LocalDateTime.now());
	}
}
