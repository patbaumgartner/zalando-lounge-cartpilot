package com.patbaumgartner.zalando.lounge.cartpilot.domain.port.out;

import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.Campaign;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.DiscoveredProduct;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.ProductDetails;

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
	 * Navigates to the product detail page and returns its enrichment: the selectable
	 * (in-stock) size labels and the resolved gender. Campaign listing pages expose
	 * neither, so this is used to enrich brand/price candidates before the size and
	 * gender gates are applied. Returns {@link ProductDetails#empty()} when the page
	 * cannot be read.
	 */
	ProductDetails fetchProductDetails(String productUrl);

	/**
	 * Navigates to the product URL, selects the given size, and clicks "Add to cart".
	 * Returns true when the cart badge confirms success.
	 */
	boolean addToCart(String productUrl, String size);

	/** Removes an item identified by its product URL from the cart. */
	void removeFromCart(String productUrl);

	/**
	 * Removes all items currently visible in the cart and returns removed count.
	 */
	int clearCart();

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
