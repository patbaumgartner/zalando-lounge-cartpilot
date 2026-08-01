package com.patbaumgartner.zalando.lounge.cartpilot.domain.port.out;

import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.Campaign;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.CartAddResult;
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
	 * Adds the given size of the product to the basket and confirms it landed there.
	 * Reports <em>why</em> an add failed so a bot-wall rejection is not mistaken for a
	 * sold-out size.
	 */
	CartAddResult addToCart(String productUrl, String size);

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

	/**
	 * Refreshes a cart reservation by removing the item and immediately re-adding it.
	 * Mutating the basket resets Zalando's server-side reservation timer, which a
	 * read-only presence check does not — this is what actually prolongs the hold during
	 * keep-alive. The result distinguishes a sold-out article (the hold is genuinely
	 * gone) from a bot-wall rejection (the hold may well still stand).
	 */
	CartAddResult refreshCartItem(String productUrl, String size);

	/** Verifies the session is still alive; re-authenticates if needed. */
	void ensureAuthenticated();

	/**
	 * Actively probes the browser endpoint before a scan: reconnects if the long-lived
	 * connection dropped and opens (then closes) a throwaway page to prove the remote
	 * browser and context are genuinely alive. Throws {@link IllegalStateException}
	 * naming the endpoint and connection state when the browser server is unreachable or
	 * never established a context, so a dead browser is reported distinctly from a login
	 * failure.
	 */
	void verifyBrowserAvailable();

	class BrowserException extends RuntimeException {

		public BrowserException(String message, Throwable cause) {
			super(message, cause);
		}

	}

}
