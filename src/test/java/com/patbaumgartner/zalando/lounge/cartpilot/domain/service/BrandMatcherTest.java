package com.patbaumgartner.zalando.lounge.cartpilot.domain.service;

import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.BrandTier;
import com.patbaumgartner.zalando.lounge.cartpilot.testdata.ProfileTestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("BrandMatcher")
class BrandMatcherTest {

	private BrandMatcher matcher;

	@BeforeEach
	void setUp() {
		matcher = new BrandMatcher();
	}

	@Nested
	@DisplayName("Tier matching")
	class TierMatching {

		@Test
		@DisplayName("finds TIER_1 for exact brand match")
		void exactTier1Match() {
			var profile = ProfileTestData.aProfile().withTier1Brand("Mammut").build();
			assertThat(matcher.findTier("Mammut", profile)).contains(BrandTier.TIER_1);
		}

		@Test
		@DisplayName("finds TIER_2 for exact brand match")
		void exactTier2Match() {
			var profile = ProfileTestData.aProfile().withTier2Brand("Jack Wolfskin").build();
			assertThat(matcher.findTier("Jack Wolfskin", profile)).contains(BrandTier.TIER_2);
		}

		@Test
		@DisplayName("finds TIER_1 with fuzzy match within distance 2")
		void fuzzyTier1Match() {
			var profile = ProfileTestData.aProfile().withTier1Brand("Mammut").build();
			assertThat(matcher.findTier("Mamut", profile)).contains(BrandTier.TIER_1);
		}

		@Test
		@DisplayName("is case-insensitive")
		void caseInsensitive() {
			var profile = ProfileTestData.aProfile().withTier1Brand("Mammut").build();
			assertThat(matcher.findTier("MAMMUT", profile)).contains(BrandTier.TIER_1);
			assertThat(matcher.findTier("mammut", profile)).contains(BrandTier.TIER_1);
		}

		@Test
		@DisplayName("ignores punctuation in brand names")
		void ignoresPunctuation() {
			var profile = ProfileTestData.aProfile().withTier1Brand("Arc'teryx").build();
			// normalised "arcteryx" vs "arcteryx" → distance 0
			assertThat(matcher.findTier("Arc'teryx", profile)).contains(BrandTier.TIER_1);
		}

		@Test
		@DisplayName("returns empty when no match")
		void noMatch() {
			var profile = ProfileTestData.aProfile().withTier1Brand("Mammut").build();
			assertThat(matcher.findTier("Patagonia", profile)).isEmpty();
		}

		@Test
		@DisplayName("returns TIER_1 over TIER_2 when brand matches both (should not happen, but TIER_1 first)")
		void tier1TakesPrecedence() {
			var profile = ProfileTestData.aProfile().withTier1Brand("Mammut").withTier2Brand("Mammut").build();
			assertThat(matcher.findTier("Mammut", profile)).contains(BrandTier.TIER_1);
		}

		@Test
		@DisplayName("resolves brand alias before matching")
		void resolvesBrandAlias() {
			var profile = ProfileTestData.aProfile()
				.withTier1Brand("The North Face")
				.withBrandAlias("TNF", "The North Face")
				.build();
			assertThat(matcher.findTier("TNF", profile)).contains(BrandTier.TIER_1);
		}

	}

	@Nested
	@DisplayName("Similar brand matching")
	class SimilarMatching {

		@Test
		@DisplayName("matches accent variants (Fjällräven == Fjallraven)")
		void matchesAccentVariants() {
			var profile = ProfileTestData.aProfile().withTier1Brand("Fjällräven").build();
			assertThat(matcher.findTier("Fjallraven", profile)).contains(BrandTier.TIER_1);

			var haglofs = ProfileTestData.aProfile().withTier2Brand("Haglöfs").build();
			assertThat(matcher.findTier("Haglofs", haglofs)).contains(BrandTier.TIER_2);
		}

		@Test
		@DisplayName("matches when listed brand is a word-subset of the scraped brand")
		void matchesWordSubset() {
			var profile = ProfileTestData.aProfile().withTier1Brand("The North Face").build();
			assertThat(matcher.findTier("North Face", profile)).contains(BrandTier.TIER_1);

			var salomon = ProfileTestData.aProfile().withTier1Brand("Salomon").build();
			assertThat(matcher.findTier("Salomon S-Lab", salomon)).contains(BrandTier.TIER_1);

			var bergans = ProfileTestData.aProfile().withTier2Brand("Bergans of Norway").build();
			assertThat(matcher.findTier("Bergans", bergans)).contains(BrandTier.TIER_2);
		}

		@Test
		@DisplayName("does not match on a shared short connector word only")
		void doesNotMatchOnSharedFirstWordOnly() {
			var profile = ProfileTestData.aProfile().withTier1Brand("Jack & Jones").build();
			assertThat(matcher.findTier("Jack Wolfskin", profile)).isEmpty();
		}

	}

	@Nested
	@DisplayName("Fuzzy tolerance scales with brand length")
	class FuzzyTolerance {

		@ParameterizedTest(name = "{0} must not match tier-1 brand {1}")
		@CsvSource({ "Nine,Nike", "Gas,Gap", "Bogs,Boss", "Hugs,Hugo", "Lowe,Lowa", "Vans,Vaus", "ECCO,ECO" })
		@DisplayName("short brands within two edits are not treated as the same brand")
		void rejectsShortNearMisses(String scrapedBrand, String tier1Brand) {
			var profile = ProfileTestData.aProfile().withTier1Brand(tier1Brand).build();

			assertThat(matcher.findTier(scrapedBrand, profile)).isEmpty();
		}

		@ParameterizedTest(name = "{0} still matches tier-1 brand {1}")
		@CsvSource({ "Mamut,Mammut", "Fjallraven,Fjällräven", "Patagonia,Patagonia", "Colombia,Columbia",
				"Icebraker,Icebreaker" })
		@DisplayName("longer brands still absorb genuine typos")
		void acceptsLongerTypos(String scrapedBrand, String tier1Brand) {
			var profile = ProfileTestData.aProfile().withTier1Brand(tier1Brand).build();

			assertThat(matcher.findTier(scrapedBrand, profile)).contains(BrandTier.TIER_1);
		}

		@Test
		@DisplayName("a short listed brand still matches as a word of a longer scraped brand")
		void shortBrandStillMatchesAsWord() {
			var profile = ProfileTestData.aProfile().withTier1Brand("Levi").build();

			assertThat(matcher.findTier("Levi's", profile)).contains(BrandTier.TIER_1);
		}

	}

}
