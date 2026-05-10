package com.patbaumgartner.zalando.lounge.cartpilot.domain.port.out;

import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.PurchasedItem;

import java.util.Set;

/** Repository port for purchase history — drives gate 4 of the filter. */
public interface PurchasedItemPort {

	boolean hasProfilePurchasedProduct(Long profileId, Long productId);

	/**
	 * Returns all product IDs purchased by a profile (used for bulk gate checks).
	 */
	Set<Long> findProductIdsByProfileId(Long profileId);

	PurchasedItem save(PurchasedItem item);

}
