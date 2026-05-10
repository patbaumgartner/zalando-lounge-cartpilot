package com.patbaumgartner.zalando.lounge.cartpilot.domain.model;

import com.patbaumgartner.zalando.lounge.cartpilot.testdata.ProfileTestData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Profile")
class ProfileTest {

	@Nested
	@DisplayName("sizeFor")
	class SizeFor {

		@Test
		@DisplayName("returns the size when set for the category")
		void returnsSizeForCategory() {
			var profile = ProfileTestData.aProfile().withSize(Category.SHOES, "43").build();

			assertThat(profile.sizeFor(Category.SHOES)).contains("43");
		}

		@Test
		@DisplayName("returns empty when no size set for category")
		void emptyWhenNoSizeSet() {
			var profile = ProfileTestData.aProfile().build();

			assertThat(profile.sizeFor(Category.SWIMWEAR)).isEmpty();
		}

	}

	@Nested
	@DisplayName("maxPriceFor")
	class MaxPriceFor {

		@Test
		@DisplayName("returns max price for SHOES category")
		void returnsMaxPriceForShoes() {
			var profile = ProfileTestData.aProfile().withMaxPriceShoes(new BigDecimal("200")).build();

			assertThat(profile.maxPriceFor(Category.SHOES)).contains(new BigDecimal("200"));
		}

		@Test
		@DisplayName("returns max price for jacket category")
		void returnsMaxPriceForJackets() {
			var profile = ProfileTestData.aProfile().withMaxPriceJackets(new BigDecimal("400")).build();

			assertThat(profile.maxPriceFor(Category.JACKETS)).contains(new BigDecimal("400"));
		}

		@Test
		@DisplayName("returns max price for clothing categories")
		void returnsMaxPriceForClothing() {
			var profile = ProfileTestData.aProfile().withMaxPriceClothing(new BigDecimal("150")).build();

			assertThat(profile.maxPriceFor(Category.SHIRTS)).contains(new BigDecimal("150"));
		}

	}

	@Nested
	@DisplayName("Brand aliases")
	class BrandAliases {

		@Test
		@DisplayName("resolves a known alias to its canonical brand name")
		void resolvesKnownAlias() {
			var profile = ProfileTestData.aProfile().withBrandAlias("TNF", "The North Face").build();

			assertThat(profile.resolveAlias("TNF")).isEqualTo("The North Face");
		}

		@Test
		@DisplayName("returns original brand name when no alias found")
		void returnsOriginalWhenNoAlias() {
			var profile = ProfileTestData.aProfile().build();

			assertThat(profile.resolveAlias("Mammut")).isEqualTo("Mammut");
		}

	}

	@Nested
	@DisplayName("Profile mutations (return new instances)")
	class Mutations {

		@Test
		@DisplayName("withSize returns new profile with added size")
		void withSize() {
			var original = ProfileTestData.aProfile().build();
			var updated = original.withSize(Category.SWIMWEAR, "L");

			assertThat(updated.sizeFor(Category.SWIMWEAR)).contains("L");
			assertThat(original.sizeFor(Category.SWIMWEAR)).isEmpty();
		}

		@Test
		@DisplayName("activate mutates profile to active state")
		void activate() {
			var profile = ProfileTestData.aProfile().inactive().build();
			assertThat(profile.active()).isFalse();

			profile.activate();

			assertThat(profile.active()).isTrue();
		}

		@Test
		@DisplayName("deactivate mutates profile to inactive state")
		void deactivate() {
			var profile = ProfileTestData.aProfile().build();
			assertThat(profile.active()).isTrue();

			profile.deactivate();

			assertThat(profile.active()).isFalse();
		}

		@Test
		@DisplayName("withBrandInTier1 adds brand to TIER_1")
		void withBrandInTier1() {
			var profile = ProfileTestData.aProfile().build();
			var updated = profile.withBrandInTier(BrandTier.TIER_1, "Patagonia");

			assertThat(updated.brandTier1()).contains("Patagonia");
		}

		@Test
		@DisplayName("withBrandRemoved removes brand from tier lists")
		void withBrandRemoved() {
			var profile = ProfileTestData.aProfile().withTier1Brand("Mammut").withTier2Brand("Jack Wolfskin").build();

			var updated = profile.withBrandRemoved("Mammut");

			assertThat(updated.brandTier1()).doesNotContain("Mammut");
			assertThat(updated.brandTier2()).contains("Jack Wolfskin");
		}

	}

	@Nested
	@DisplayName("Gender compatibility")
	class GenderCompatibility {

		@Test
		@DisplayName("MEN profile matches MEN and UNISEX products")
		void menCompatibility() {
			assertThat(Gender.MEN.isCompatibleWith(Gender.MEN)).isTrue();
			assertThat(Gender.MEN.isCompatibleWith(Gender.UNISEX)).isTrue();
			assertThat(Gender.MEN.isCompatibleWith(Gender.WOMEN)).isFalse();
		}

		@Test
		@DisplayName("WOMEN profile matches WOMEN and UNISEX products")
		void womenCompatibility() {
			assertThat(Gender.WOMEN.isCompatibleWith(Gender.WOMEN)).isTrue();
			assertThat(Gender.WOMEN.isCompatibleWith(Gender.UNISEX)).isTrue();
			assertThat(Gender.WOMEN.isCompatibleWith(Gender.MEN)).isFalse();
		}

		@Test
		@DisplayName("UNISEX profile matches everything")
		void unisexCompatibility() {
			assertThat(Gender.UNISEX.isCompatibleWith(Gender.MEN)).isTrue();
			assertThat(Gender.UNISEX.isCompatibleWith(Gender.WOMEN)).isTrue();
			assertThat(Gender.UNISEX.isCompatibleWith(Gender.UNISEX)).isTrue();
		}

	}

}
