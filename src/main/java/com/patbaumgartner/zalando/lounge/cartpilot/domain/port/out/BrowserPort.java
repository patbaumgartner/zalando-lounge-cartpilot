package com.patbaumgartner.zalando.lounge.cartpilot.domain.port.out;

import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.Campaign;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.DiscoveredProduct;

import java.util.List;

/**
 * Browser port — abstracts Playwright away from the domain. All methods may throw
 * {@link BrowserException} on failure.
 */
public interface BrowserPort {

	/** Opens the campaign listing page and returns today's active campaigns. */
	List<Campaign> fetchTodayCampaigns();

	/** Scrapes all products from a single campaign page. */
	List<DiscoveredProduct> scrapeProducts(Campaign campaign);

	/**
	 * Navigates to the product URL, selects the given size, and clicks "Add to cart".
	 * Returns true when the cart badge confirms success.
	 */
	boolean addToCart(String productUrl, String size);

	/** Removes an item identified by its product URL from the cart. */
	void removeFromCart(String productUrl);

	/**
	 * Reloads the cart page and checks whether the item is still present.
	 */
	boolean isItemInCart(String productUrl);

	/** Verifies the session is still alive; re-authenticates if needed. */
	void ensureAuthenticated();

	class BrowserException extends RuntimeException {

		public BrowserException(String message, Throwable cause) {
			super(message, cause);
		}

	}

}
