package com.patbaumgartner.zalando.lounge.cartpilot.domain.port.out;

import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.PurchasedItem;

import java.util.Set;

/** Repository port for purchase history — drives gate 4 of the filter. */
public interface PurchasedItemPort {

	boolean hasProfilePurchasedProduct(Long profileId, Long productId);

	/**
	 * Returns the stable article keys a profile has already bought, for the
	 * already-purchased gate. Keyed on the article rather than the discovered-product row
	 * because every scan re-inserts its products with fresh ids.
	 */
	Set<String> findPurchasedArticleKeysByProfileId(Long profileId);

	PurchasedItem save(PurchasedItem item);

}
