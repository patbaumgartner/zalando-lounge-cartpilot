package com.patbaumgartner.zalando.lounge.cartpilot.domain.model;

import java.util.List;
import java.util.Objects;

/**
 * Enrichment scraped from a product detail page. Campaign listing cards expose neither
 * sizes nor gender, so both are fetched lazily for brand/price candidates before the size
 * and gender gates are applied.
 *
 * @param sizes the selectable (in-stock) size labels, possibly empty when every size is
 * sold out
 * @param gender the resolved gender, or {@link Gender#UNISEX} when it could not be
 * determined
 */
public record ProductDetails(List<String> sizes, Gender gender) {

	public ProductDetails {
		sizes = List.copyOf(sizes);
		gender = Objects.requireNonNull(gender, "gender");
	}

	/** Empty enrichment: no sizes and unknown ({@link Gender#UNISEX}) gender. */
	public static ProductDetails empty() {
		return new ProductDetails(List.of(), Gender.UNISEX);
	}
}
