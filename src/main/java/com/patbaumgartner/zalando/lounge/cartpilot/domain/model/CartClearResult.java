package com.patbaumgartner.zalando.lounge.cartpilot.domain.model;

/**
 * Outcome of emptying the basket.
 *
 * <p>
 * {@code cartReadable == false} deliberately separates "the basket could not be read"
 * from "the basket was already empty". Collapsing the two made a blocked read look like a
 * successful clear, after which every {@code IN_CART} reservation was written off as
 * rejected while the items were still sitting in the basket.
 *
 * @param cartReadable whether the basket contents could be read at all
 * @param removedCount line items confirmed removed
 * @param failedCount line items the shop refused to remove
 */
public record CartClearResult(boolean cartReadable, int removedCount, int failedCount) {

	public static CartClearResult unreadable() {
		return new CartClearResult(false, 0, 0);
	}

	public static CartClearResult of(int removedCount, int failedCount) {
		return new CartClearResult(true, removedCount, failedCount);
	}

	/** True when the basket is known to be empty now. */
	public boolean isCartEmptyNow() {
		return cartReadable && failedCount == 0;
	}

	public String describe() {
		if (!cartReadable) {
			return "the basket could not be read";
		}
		return failedCount == 0 ? "removed %d item(s)".formatted(removedCount)
				: "removed %d item(s), %d could not be removed".formatted(removedCount, failedCount);
	}

}
