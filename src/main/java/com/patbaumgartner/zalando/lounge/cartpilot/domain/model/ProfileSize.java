package com.patbaumgartner.zalando.lounge.cartpilot.domain.model;

/**
 * Immutable value object – one size for one category belonging to one profile.
 */
public record ProfileSize(Long profileId, Category category, String size) {

	public ProfileSize {
		if (size == null || size.isBlank()) {
			throw new IllegalArgumentException("size must not be blank");
		}
	}
}
