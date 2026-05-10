package com.patbaumgartner.zalando.lounge.cartpilot.domain.service;

import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.Category;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.Decision;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.Gender;
import com.patbaumgartner.zalando.lounge.cartpilot.testdata.ProductTestData;
import com.patbaumgartner.zalando.lounge.cartpilot.testdata.ProfileTestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ProductFilterService")
class ProductFilterServiceTest {

	private ProductFilter filterService;

	@BeforeEach
	void setUp() {
		filterService = new ProductFilter(new BrandMatcher(), new ProductScorer());
	}

	@Nested
	@DisplayName("Happy path")
	class HappyPath {

		@Test
		@DisplayName("returns AUTO_RESERVE for Tier-1 brand matching all gates")
		void autoReserveForTier1() {
			var profile = ProfileTestData.aProfile()
				.withGender(Gender.MEN)
				.withSize(Category.JACKETS, "52")
				.withTier1Brand("Mammut")
				.withMaxPriceJackets(new BigDecimal("400"))
				.build();

			var product = ProductTestData.aProduct()
				.withBrand("Mammut")
				.withCategory(Category.JACKETS)
				.withGender(Gender.MEN)
				.withSizesAvailable(List.of("48", "50", "52"))
				.withLoungePrice(new BigDecimal("189"))
				.build();

			var results = filterService.filter(List.of(product), profile, Set.of());

			assertThat(results).hasSize(1);
			assertThat(results.get(0).decision()).isEqualTo(Decision.AUTO_RESERVE);
			assertThat(results.get(0).size()).isEqualTo("52");
		}

		@Test
		@DisplayName("returns NOTIFY_ONLY for Tier-2 brand")
		void notifyOnlyForTier2() {
			var profile = ProfileTestData.aProfile()
				.withGender(Gender.MEN)
				.withSize(Category.JACKETS, "52")
				.withTier2Brand("Jack Wolfskin")
				.build();

			var product = ProductTestData.aProduct()
				.withBrand("Jack Wolfskin")
				.withCategory(Category.JACKETS)
				.withGender(Gender.MEN)
				.withSizesAvailable(List.of("50", "52", "54"))
				.withLoungePrice(new BigDecimal("79"))
				.build();

			var results = filterService.filter(List.of(product), profile, Set.of());

			assertThat(results).hasSize(1);
			assertThat(results.get(0).decision()).isEqualTo(Decision.NOTIFY_ONLY);
		}

		@Test
		@DisplayName("matches UNISEX product for MEN profile")
		void unisexMatchesMen() {
			var profile = ProfileTestData.aProfile()
				.withGender(Gender.MEN)
				.withSize(Category.JACKETS, "52")
				.withTier1Brand("Mammut")
				.build();

			var product = ProductTestData.aProduct()
				.withGender(Gender.UNISEX)
				.withSizesAvailable(List.of("52"))
				.build();

			var results = filterService.filter(List.of(product), profile, Set.of());

			assertThat(results).hasSize(1);
		}

	}

	@Nested
	@DisplayName("Hard gates — each causes silent skip")
	class HardGates {

		@Test
		@DisplayName("Gate 1: skips when gender does not match")
		void genderMismatch() {
			var profile = ProfileTestData.aProfile()
				.withGender(Gender.MEN)
				.withSize(Category.JACKETS, "52")
				.withTier1Brand("Mammut")
				.build();

			var product = ProductTestData.aProduct().withGender(Gender.WOMEN).build();

			assertThat(filterService.filter(List.of(product), profile, Set.of())).isEmpty();
		}

		@Test
		@DisplayName("Gate 2a: skips when profile has no size for this category")
		void noSizeForCategory() {
			var profile = ProfileTestData.aProfile()
				.withGender(Gender.MEN)
				// no SHOES size set
				.withoutSize(Category.SHOES)
				.withTier1Brand("Mammut")
				.build();

			var product = ProductTestData.aProduct()
				.withCategory(Category.SHOES)
				.withGender(Gender.MEN)
				.withSizesAvailable(List.of("43"))
				.build();

			assertThat(filterService.filter(List.of(product), profile, Set.of())).isEmpty();
		}

		@Test
		@DisplayName("Gate 2b: skips when profile's size is not available")
		void sizeNotAvailable() {
			var profile = ProfileTestData.aProfile()
				.withGender(Gender.MEN)
				.withSize(Category.JACKETS, "58")
				.withTier1Brand("Mammut")
				.build();

			var product = ProductTestData.aProduct().withSizesAvailable(List.of("48", "50", "52")).build();

			assertThat(filterService.filter(List.of(product), profile, Set.of())).isEmpty();
		}

		@Test
		@DisplayName("Gate 3: skips when price exceeds max price")
		void priceExceedsMax() {
			var profile = ProfileTestData.aProfile()
				.withGender(Gender.MEN)
				.withSize(Category.JACKETS, "52")
				.withTier1Brand("Mammut")
				.withMaxPriceJackets(new BigDecimal("100"))
				.build();

			var product = ProductTestData.aProduct()
				.withLoungePrice(new BigDecimal("200"))
				.withSizesAvailable(List.of("52"))
				.build();

			assertThat(filterService.filter(List.of(product), profile, Set.of())).isEmpty();
		}

		@Test
		@DisplayName("Gate 4: skips when product was already purchased by this profile")
		void alreadyPurchased() {
			var profile = ProfileTestData.aProfile()
				.withGender(Gender.MEN)
				.withSize(Category.JACKETS, "52")
				.withTier1Brand("Mammut")
				.build();

			var product = ProductTestData.aProduct().withId(99L).withSizesAvailable(List.of("52")).build();

			assertThat(filterService.filter(List.of(product), profile, Set.of(99L))).isEmpty();
		}

		@Test
		@DisplayName("skips when brand is not in any tier")
		void noTierMatch() {
			var profile = ProfileTestData.aProfile()
				.withGender(Gender.MEN)
				.withSize(Category.JACKETS, "52")
				.withTier1Brand("Mammut")
				.build();

			var product = ProductTestData.aProduct()
				.withBrand("Unknown Brand")
				.withSizesAvailable(List.of("52"))
				.build();

			assertThat(filterService.filter(List.of(product), profile, Set.of())).isEmpty();
		}

	}

	@Test
	@DisplayName("evaluates multiple products against a profile independently")
	void multipleProducts() {
		var profile = ProfileTestData.aProfile()
			.withGender(Gender.MEN)
			.withSize(Category.JACKETS, "52")
			.withTier1Brand("Mammut")
			.withTier2Brand("Jack Wolfskin")
			.build();

		var tier1Product = ProductTestData.aProduct()
			.withId(1L)
			.withBrand("Mammut")
			.withSizesAvailable(List.of("52"))
			.build();

		var tier2Product = ProductTestData.aProduct()
			.withId(2L)
			.withBrand("Jack Wolfskin")
			.withSizesAvailable(List.of("52"))
			.build();

		var noMatchProduct = ProductTestData.aProduct()
			.withId(3L)
			.withBrand("Gucci")
			.withSizesAvailable(List.of("52"))
			.build();

		var results = filterService.filter(List.of(tier1Product, tier2Product, noMatchProduct), profile, Set.of());

		assertThat(results).hasSize(2);
		assertThat(results).extracting(r -> r.decision())
			.containsExactlyInAnyOrder(Decision.AUTO_RESERVE, Decision.NOTIFY_ONLY);
	}

	@Nested
	@DisplayName("prefilterCandidates (size gate deferred)")
	class PrefilterCandidates {

		@Test
		@DisplayName("keeps brand/price/gender matches even when no sizes are known yet")
		void keepsCandidatesWithoutSizes() {
			var profile = ProfileTestData.aProfile()
				.withGender(Gender.MEN)
				.withSize(Category.JACKETS, "52")
				.withTier1Brand("Mammut")
				.withMaxPriceJackets(new BigDecimal("400"))
				.build();

			// No sizes scraped from the listing card yet.
			var product = ProductTestData.aProduct()
				.withBrand("Mammut")
				.withCategory(Category.JACKETS)
				.withGender(Gender.MEN)
				.withSizesAvailable(List.of())
				.withLoungePrice(new BigDecimal("189"))
				.build();

			// The full filter rejects it (size gate), but the prefilter keeps it.
			assertThat(filterService.filter(List.of(product), profile, Set.of())).isEmpty();
			assertThat(filterService.prefilterCandidates(List.of(product), profile, Set.of())).containsExactly(product);
		}

		@Test
		@DisplayName("drops products whose brand, price, gender or category-size config fail")
		void dropsNonCandidates() {
			var profile = ProfileTestData.aProfile()
				.withGender(Gender.MEN)
				.withSize(Category.JACKETS, "52")
				.withTier1Brand("Mammut")
				.withMaxPriceJackets(new BigDecimal("100"))
				.build();

			var wrongBrand = ProductTestData.aProduct()
				.withId(1L)
				.withBrand("Gucci")
				.withSizesAvailable(List.of())
				.build();
			var tooExpensive = ProductTestData.aProduct()
				.withId(2L)
				.withBrand("Mammut")
				.withLoungePrice(new BigDecimal("200"))
				.withSizesAvailable(List.of())
				.build();
			var wrongGender = ProductTestData.aProduct()
				.withId(3L)
				.withBrand("Mammut")
				.withGender(Gender.WOMEN)
				.withLoungePrice(new BigDecimal("50"))
				.withSizesAvailable(List.of())
				.build();

			assertThat(filterService.prefilterCandidates(List.of(wrongBrand, tooExpensive, wrongGender), profile,
					Set.of()))
				.isEmpty();
		}

		@Test
		@DisplayName("drops products already purchased by the profile")
		void dropsPurchased() {
			var profile = ProfileTestData.aProfile()
				.withGender(Gender.MEN)
				.withSize(Category.JACKETS, "52")
				.withTier1Brand("Mammut")
				.build();

			var product = ProductTestData.aProduct()
				.withId(42L)
				.withBrand("Mammut")
				.withSizesAvailable(List.of())
				.build();

			assertThat(filterService.prefilterCandidates(List.of(product), profile, Set.of(42L))).isEmpty();
		}

	}

}
