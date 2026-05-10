package com.patbaumgartner.zalando.lounge.cartpilot.domain.service;

import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.BrandTier;
import com.patbaumgartner.zalando.lounge.cartpilot.testdata.ProfileTestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

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
	@DisplayName("findSimilarIn")
	class FindSimilarIn {

		@Test
		@DisplayName("finds similar brand within known list")
		void findsSimilar() {
			var known = List.of("Mammut", "Arc'teryx", "Salomon");
			assertThat(matcher.findSimilarIn("Mamut", known)).contains("Mammut");
		}

		@Test
		@DisplayName("returns empty when no similar brand found")
		void returnsEmptyWhenNoMatch() {
			var known = List.of("Mammut", "Arc'teryx");
			assertThat(matcher.findSimilarIn("Patagonia", known)).isEmpty();
		}

	}

}
