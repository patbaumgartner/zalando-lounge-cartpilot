package com.patbaumgartner.zalando.lounge.cartpilot.config;

/**
 * Single source of truth for building Zalando Lounge URLs from the configured base URL.
 */
public final class ZalandoUrls {

	private ZalandoUrls() {
	}

	/** Returns the absolute basket/cart URL for the given base URL. */
	public static String cartUrl(String baseUrl) {
		return baseUrl.endsWith("/") ? baseUrl + "cart" : baseUrl + "/cart";
	}

	/**
	 * Returns the absolute cart API endpoint for the given base URL. A {@code GET}
	 * returns the current basket as JSON ({@code {"items":[{"configSku":...}]}}); the
	 * {@code /cart} page itself only renders recommendation carousels, so cart
	 * verification must go through this endpoint.
	 */
	public static String cartApiUrl(String baseUrl) {
		var base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
		return base + "/api/phoenix/stockcart/cart";
	}

	/**
	 * Resolves a possibly-relative product URL against the base URL. Absolute URLs are
	 * returned unchanged; blank input falls back to the cart URL.
	 */
	public static String resolveUrl(String baseUrl, String url) {
		if (url == null || url.isBlank()) {
			return cartUrl(baseUrl);
		}
		if (url.startsWith("http://") || url.startsWith("https://")) {
			return url;
		}
		var base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
		return url.startsWith("/") ? base + url : base + "/" + url;
	}

}
