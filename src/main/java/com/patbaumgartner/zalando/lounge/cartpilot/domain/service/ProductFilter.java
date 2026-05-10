package com.patbaumgartner.zalando.lounge.cartpilot.domain.service;

import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.DiscoveredProduct;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.FilterResult;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.Profile;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Pure domain service: applies hard gates then scores each product × profile.
 *
 * Hard gates (any failure → silent skip for this profile): 1. Campaign gender matches
 * profile gender (or product is UNISEX). 2. Profile has a size for this category and that
 * size is available. 3. Lounge price is under the profile's cap for this category. 4.
 * Profile has not already purchased this product.
 *
 * Brand matching (Levenshtein ≤ 2): No match → silent skip.
 */
public class ProductFilter {

	private final BrandMatcher brandMatcher;

	private final ProductScorer productScorer;

	public ProductFilter(BrandMatcher brandMatcher, ProductScorer productScorer) {
		this.brandMatcher = brandMatcher;
		this.productScorer = productScorer;
	}

	/**
	 * Returns one {@link FilterResult} for each product-profile pair that passes all hard
	 * gates and has a brand tier match.
	 * @param products products to evaluate
	 * @param profile the profile to match against
	 * @param purchasedProductIds ids of products already purchased by this profile
	 */
	public List<FilterResult> filter(List<DiscoveredProduct> products, Profile profile, Set<Long> purchasedProductIds) {
		var results = new ArrayList<FilterResult>();

		for (var product : products) {
			evaluate(product, profile, purchasedProductIds).ifPresent(results::add);
		}

		return results;
	}

	// ── Private ────────────────────────────────────────────────

	private Optional<FilterResult> evaluate(DiscoveredProduct product, Profile profile, Set<Long> purchasedProductIds) {
		// Gate 1: gender
		if (!product.isGenderCompatibleWith(profile.gender())) {
			return Optional.empty();
		}

		// Gate 2: size
		var profileSize = profile.sizeFor(product.category());
		if (profileSize.isEmpty()) {
			return Optional.empty();
		}
		if (!product.hasSizeAvailable(profileSize.get())) {
			return Optional.empty();
		}

		// Gate 3: price cap
		var maxPrice = profile.maxPriceFor(product.category());
		if (!product.isPriceUnder(maxPrice.orElse(null))) {
			return Optional.empty();
		}

		// Gate 4: not already purchased
		if (product.id() != null && purchasedProductIds.contains(product.id())) {
			return Optional.empty();
		}

		// Brand match
		return brandMatcher.findTier(product.brand(), profile).map(tier -> {
			var scored = productScorer.score(product, tier);
			return new FilterResult(product, profile, profileSize.get(), scored.decision(), tier, scored.score());
		});
	}

}
