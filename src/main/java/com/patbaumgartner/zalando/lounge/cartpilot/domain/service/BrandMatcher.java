package com.patbaumgartner.zalando.lounge.cartpilot.domain.service;

import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.BrandTier;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.Profile;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Matches a raw brand string against a profile's tier lists. Matching is tolerant to
 * spelling variants in three escalating ways:
 * <ol>
 * <li>Exact match on an accent-folded, alphanumeric-only form (so {@code Fjällräven} ==
 * {@code Fjallraven} and {@code Arc'teryx} == {@code Arcteryx}).</li>
 * <li>Fuzzy match via Levenshtein ≤ 2 to absorb typos (e.g. {@code Mamut}).</li>
 * <li>"Similar" match where one brand's words form a contiguous run inside the other
 * (e.g. {@code North Face} ⊂ {@code The North Face}, {@code Salomon} ⊂
 * {@code Salomon S-Lab}).</li>
 * </ol>
 *
 * Alias resolution (e.g. TNF → The North Face) is applied before matching.
 */
public class BrandMatcher {

	private static final int MAX_EDIT_DISTANCE = 2;

	/** Single-word "similar" matches must be at least this long to avoid noise. */
	private static final int MIN_SINGLE_WORD_LENGTH = 4;

	/**
	 * Returns the brand tier for the given brand within the given profile, or empty if no
	 * match is found in either tier.
	 */
	public Optional<BrandTier> findTier(String rawBrand, Profile profile) {
		String resolved = profile.resolveAlias(rawBrand);

		if (matchesAny(resolved, profile.brandTier1())) {
			return Optional.of(BrandTier.TIER_1);
		}
		if (matchesAny(resolved, profile.brandTier2())) {
			return Optional.of(BrandTier.TIER_2);
		}

		return Optional.empty();
	}

	// ── Private helpers ────────────────────────────────────────

	private boolean matchesAny(String brand, Iterable<String> tierBrands) {
		for (String tierBrand : tierBrands) {
			if (matches(brand, tierBrand)) {
				return true;
			}
		}
		return false;
	}

	private boolean matches(String rawA, String rawB) {
		String a = normalise(rawA);
		String b = normalise(rawB);
		if (a.isEmpty() || b.isEmpty()) {
			return false;
		}
		if (a.equals(b)) {
			return true;
		}
		if (LevenshteinDistance.compute(a, b) <= MAX_EDIT_DISTANCE) {
			return true;
		}
		// "Similar" brands: one name's words appear as a contiguous run in the other.
		List<String> wordsA = words(rawA);
		List<String> wordsB = words(rawB);
		return containsSequence(wordsA, wordsB) || containsSequence(wordsB, wordsA);
	}

	/**
	 * Lower-cased, accent-folded, alphanumeric-only form for exact/fuzzy comparison.
	 */
	private String normalise(String brand) {
		return foldAccents(brand).toLowerCase().replaceAll("[^a-z0-9]", "");
	}

	/**
	 * Lower-cased, accent-folded words, splitting on any non-alphanumeric character.
	 */
	private List<String> words(String brand) {
		return Arrays.stream(foldAccents(brand).toLowerCase().split("[^a-z0-9]+")).filter(w -> !w.isBlank()).toList();
	}

	private String foldAccents(String value) {
		return Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
	}

	/**
	 * Returns true when {@code needle} occurs as a contiguous sub-sequence of
	 * {@code haystack}. A single-word needle must be reasonably long to count, so short
	 * connector words never trigger a match.
	 */
	private boolean containsSequence(List<String> haystack, List<String> needle) {
		if (needle.isEmpty() || needle.size() > haystack.size()) {
			return false;
		}
		if (needle.size() == 1 && needle.get(0).length() < MIN_SINGLE_WORD_LENGTH) {
			return false;
		}
		for (int start = 0; start + needle.size() <= haystack.size(); start++) {
			if (haystack.subList(start, start + needle.size()).equals(needle)) {
				return true;
			}
		}
		return false;
	}

}
