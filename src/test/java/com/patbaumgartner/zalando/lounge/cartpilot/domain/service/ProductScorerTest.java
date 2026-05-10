package com.patbaumgartner.zalando.lounge.cartpilot.domain.service;

import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.BrandTier;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.Decision;
import com.patbaumgartner.zalando.lounge.cartpilot.testdata.ProductTestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ProductScorer")
class ProductScorerTest {

	private ProductScorer scorer;

	@BeforeEach
	void setUp() {
		scorer = new ProductScorer();
	}

	@Test
	@DisplayName("gives 50 points and AUTO_RESERVE for Tier 1 brand with no discount bonus")
	void tier1NoBonus() {
		var product = ProductTestData.aProduct().withDiscountPct(10).build();
		var result = scorer.score(product, BrandTier.TIER_1);

		assertThat(result.score()).isEqualTo(50);
		assertThat(result.decision()).isEqualTo(Decision.AUTO_RESERVE);
	}

	@Test
	@DisplayName("gives 30 points and NOTIFY_ONLY for Tier 2 brand with no discount bonus")
	void tier2NoBonus() {
		var product = ProductTestData.aProduct().withDiscountPct(10).build();
		var result = scorer.score(product, BrandTier.TIER_2);

		assertThat(result.score()).isEqualTo(30);
		assertThat(result.decision()).isEqualTo(Decision.NOTIFY_ONLY);
	}

	@ParameterizedTest(name = "{0}% discount → +{1} bonus points")
	@DisplayName("applies correct discount bonus")
	@CsvSource({ "70, 20", "60, 20", "59, 10", "40, 10", "39, 0", "0,  0" })
	void discountBonus(int discountPct, int expectedBonus) {
		var product = ProductTestData.aProduct().withDiscountPct(discountPct).build();
		var result = scorer.score(product, BrandTier.TIER_1);

		assertThat(result.score()).isEqualTo(50 + expectedBonus);
	}

	@Test
	@DisplayName("Tier 1 + 60% discount gives 70 points")
	void tier1HighDiscountGives70Points() {
		var product = ProductTestData.aProduct().withDiscountPct(60).build();
		var result = scorer.score(product, BrandTier.TIER_1);

		assertThat(result.score()).isEqualTo(70);
		assertThat(result.decision()).isEqualTo(Decision.AUTO_RESERVE);
	}

	@Test
	@DisplayName("Tier 2 + 45% discount gives 40 points")
	void tier2MediumDiscountGives40Points() {
		var product = ProductTestData.aProduct().withDiscountPct(45).build();
		var result = scorer.score(product, BrandTier.TIER_2);

		assertThat(result.score()).isEqualTo(40);
		assertThat(result.decision()).isEqualTo(Decision.NOTIFY_ONLY);
	}

}
