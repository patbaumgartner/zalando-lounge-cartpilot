package com.patbaumgartner.zalando.lounge.cartpilot.domain.service;

import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.BrandTier;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.Profile;

import java.util.Optional;

/**
 * Matches a raw brand string against a profile's tier lists using normalised string
 * comparison with Levenshtein ≤ 2 as a fuzzy fallback.
 *
 * Alias resolution (e.g. TNF → The North Face) is applied before matching.
 */
public class BrandMatcher {

	private static final int MAX_EDIT_DISTANCE = 2;

	/**
	 * Returns the brand tier for the given brand within the given profile, or empty if no
	 * match is found in either tier.
	 */
	public Optional<BrandTier> findTier(String rawBrand, Profile profile) {
		String resolved = profile.resolveAlias(rawBrand);
		String normalised = normalise(resolved);

		if (matchesAny(normalised, profile.brandTier1())) {
			return Optional.of(BrandTier.TIER_1);
		}
		if (matchesAny(normalised, profile.brandTier2())) {
			return Optional.of(BrandTier.TIER_2);
		}

		return Optional.empty();
	}

	/**
	 * Returns true when the candidate is within edit-distance 2 of any brand in the
	 * known-brand list — used for the add-brand confirmation flow.
	 */
	public Optional<String> findSimilarIn(String candidate, Iterable<String> knownBrands) {
		String normCandidate = normalise(candidate);
		for (String known : knownBrands) {
			if (LevenshteinDistance.compute(normCandidate, normalise(known)) <= MAX_EDIT_DISTANCE) {
				return Optional.of(known);
			}
		}
		return Optional.empty();
	}

	// ── Private helpers ────────────────────────────────────────

	private boolean matchesAny(String normalisedBrand, Iterable<String> tierBrands) {
		for (String tierBrand : tierBrands) {
			if (matches(normalisedBrand, normalise(tierBrand))) {
				return true;
			}
		}
		return false;
	}

	private boolean matches(String a, String b) {
		return a.equals(b) || LevenshteinDistance.compute(a, b) <= MAX_EDIT_DISTANCE;
	}

	private String normalise(String brand) {
		return brand.toLowerCase().replaceAll("[^a-z0-9]", "");
	}

}
