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
 * <li>Fuzzy match via a length-scaled Levenshtein tolerance to absorb typos (e.g.
 * {@code Mamut}); names shorter than {@value #FUZZY_MIN_LENGTH} characters must match
 * exactly.</li>
 * <li>"Similar" match where one brand's words form a contiguous run inside the other
 * (e.g. {@code North Face} ⊂ {@code The North Face}, {@code Salomon} ⊂
 * {@code Salomon S-Lab}).</li>
 * </ol>
 *
 * Alias resolution (e.g. TNF → The North Face) is applied before matching.
 */
public class BrandMatcher {

	/** Shortest normalised form that may be fuzzy-matched at all. */
	private static final int FUZZY_MIN_LENGTH = 5;

	/** From this length on, two edits are safe. */
	private static final int LONG_BRAND_LENGTH = 8;

	private static final int MAX_EDIT_DISTANCE_MEDIUM = 1;

	private static final int MAX_EDIT_DISTANCE_LONG = 2;

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
		if (isWithinEditDistance(a, b)) {
			return true;
		}
		// "Similar" brands: one name's words appear as a contiguous run in the other.
		List<String> wordsA = words(rawA);
		List<String> wordsB = words(rawB);
		return containsSequence(wordsA, wordsB) || containsSequence(wordsB, wordsA);
	}

	/**
	 * Fuzzy matching tolerance scales with the shorter name's length. A flat tolerance of
	 * two edits made unrelated short brands equal — {@code Nike}/{@code Nine},
	 * {@code Gap}/{@code Gas}, {@code Lowa}/{@code Lowe} are all within two edits — and a
	 * tier-1 false positive silently buys the wrong article. Below
	 * {@link #FUZZY_MIN_LENGTH} characters only an exact match counts; the word-subset
	 * rule still covers legitimate short forms such as {@code Levi} in {@code Levi's}.
	 */
	private boolean isWithinEditDistance(String a, String b) {
		int allowed = maxEditDistanceFor(Math.min(a.length(), b.length()));
		if (allowed == 0 || Math.abs(a.length() - b.length()) > allowed) {
			return false;
		}
		return LevenshteinDistance.compute(a, b) <= allowed;
	}

	private static int maxEditDistanceFor(int shortestLength) {
		if (shortestLength < FUZZY_MIN_LENGTH) {
			return 0;
		}
		return shortestLength < LONG_BRAND_LENGTH ? MAX_EDIT_DISTANCE_MEDIUM : MAX_EDIT_DISTANCE_LONG;
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
