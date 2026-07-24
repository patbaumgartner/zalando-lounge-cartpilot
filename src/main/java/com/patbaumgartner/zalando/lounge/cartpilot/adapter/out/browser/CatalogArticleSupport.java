package com.patbaumgartner.zalando.lounge.cartpilot.adapter.out.browser;

import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.Gender;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Shared parsing helpers for Zalando Lounge catalog article JSON.
 *
 * <p>
 * Both the campaign listing endpoint
 * ({@code /api/phoenix/catalog/events/{campaign}/articles}) and the single-article detail
 * endpoint ({@code .../articles/{sku}}) return the same article shape: a {@code gender}
 * array, a {@code simples} array carrying each size with its {@code stockStatus}, and
 * integer-cents {@code price}/{@code specialPrice} fields.
 */
final class CatalogArticleSupport {

	private CatalogArticleSupport() {
	}

	/**
	 * Returns the in-stock size labels from an article's {@code simples} array. A size is
	 * available when its {@code stockStatus} is {@code AVAILABLE}; the label is taken
	 * from {@code filterValue} (falling back to the EU country size).
	 */
	static List<String> availableSizes(JsonNode simples) {
		if (simples == null || !simples.isArray()) {
			return List.of();
		}
		var sizes = new ArrayList<String>();
		for (JsonNode simple : simples) {
			if (!"AVAILABLE".equals(simple.path("stockStatus").asString(""))) {
				continue;
			}
			String size = simple.path("filterValue").asString("").trim();
			if (size.isBlank()) {
				size = simple.path("country_sizes").path("eu").asString("").trim();
			}
			if (!size.isBlank()) {
				sizes.add(size);
			}
		}
		return sizes;
	}

	/**
	 * Resolves the article gender from its {@code gender} array (e.g. {@code ["male"]} →
	 * MEN, {@code ["female"]} → WOMEN). Mixed or unknown values fall back to
	 * {@link Gender#UNISEX} so the gender gate never over-filters.
	 */
	static Gender resolveGender(JsonNode genderArray) {
		if (genderArray == null || !genderArray.isArray() || genderArray.isEmpty()) {
			return Gender.UNISEX;
		}
		boolean male = false;
		boolean female = false;
		boolean kids = false;
		for (JsonNode g : genderArray) {
			switch (g.asString("").trim().toLowerCase(Locale.ROOT)) {
				case "male", "men", "man" -> male = true;
				case "female", "women", "woman" -> female = true;
				case "boy", "girl", "kid", "kids", "baby", "junior", "children" -> kids = true;
				default -> {
					// unknown/unisex token contributes no vote
				}
			}
		}
		if (kids && !male && !female) {
			return Gender.KIDS;
		}
		if (male && !female) {
			return Gender.MEN;
		}
		if (female && !male) {
			return Gender.WOMEN;
		}
		return Gender.UNISEX;
	}

	/**
	 * Converts an integer-cents price node (e.g. {@code 3500}) to a currency amount
	 * ({@code 35.00}). Missing or non-numeric nodes yield {@link BigDecimal#ZERO}.
	 */
	static BigDecimal centsToAmount(JsonNode node) {
		if (node == null || !node.isNumber()) {
			return BigDecimal.ZERO;
		}
		return BigDecimal.valueOf(node.asLong()).movePointLeft(2);
	}

}
