package com.patbaumgartner.zalando.lounge.cartpilot.adapter.out.browser;

import com.microsoft.playwright.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Reads and mutates the Zalando Lounge basket through the {@code stockcart} API, always
 * from inside a Patchright page (see {@link InPageHttpClient}).
 *
 * <p>
 * The {@code /cart} page itself only renders recommendation carousels, so the API is the
 * authoritative view of the basket. Every line item carries a {@code cartItemKey} (the
 * handle used to delete it), the article's {@code configSku} and the per-size
 * {@code simpleSku}.
 */
class CartApi {

	private static final Logger log = LoggerFactory.getLogger(CartApi.class);

	private final InPageHttpClient http;

	private final ObjectMapper objectMapper;

	private final String cartApiUrl;

	CartApi(InPageHttpClient http, ObjectMapper objectMapper, String cartApiUrl) {
		this.http = http;
		this.objectMapper = objectMapper;
		this.cartApiUrl = cartApiUrl;
	}

	/** One line of the basket. */
	record CartItem(String cartItemKey, String configSku, String simpleSku) {
	}

	/**
	 * The basket as the browser sees it right now.
	 *
	 * <p>
	 * {@code readable == false} deliberately separates "the cart API refused to answer"
	 * from "the cart is empty": treating a blocked read as an empty basket is what made a
	 * successful add look like a failure.
	 */
	record CartSnapshot(boolean readable, int status, List<CartItem> items) {

		static CartSnapshot unreadable(int status) {
			return new CartSnapshot(false, status, List.of());
		}

		boolean contains(String configSku) {
			return items.stream().anyMatch(item -> item.configSku().equals(configSku));
		}

		boolean containsKey(String cartItemKey) {
			return items.stream().anyMatch(item -> item.cartItemKey().equals(cartItemKey));
		}

		/**
		 * True when this snapshot holds a basket line for the article that {@code before}
		 * did not. Matching on {@code configSku} alone would call an add successful when
		 * the very same article was already in the basket in another size. When
		 * {@code before} could not be read there is nothing to diff against, so a
		 * matching line is the best available evidence.
		 */
		boolean gainedLineFor(String configSku, CartSnapshot before) {
			return items.stream()
				.filter(item -> item.configSku().equals(configSku))
				.anyMatch(item -> !before.readable() || !before.containsKey(item.cartItemKey()));
		}

	}

	CartSnapshot read(Page page) {
		var response = http.get(page, cartApiUrl);
		if (!response.ok()) {
			log.warn("Cart API read refused: {} ({})", response.describe(), response.bodySnippet());
			return CartSnapshot.unreadable(response.status());
		}
		String body = response.body() == null ? "" : response.body().strip();
		if (body.isEmpty()) {
			// An empty basket is answered with 204 No Content and no body at all.
			return new CartSnapshot(true, response.status(), List.of());
		}
		if (!response.isJson()) {
			// A logged-out session gets an HTML login/interstitial page under HTTP 200.
			log.warn("Cart API returned a non-JSON body — the session looks logged out");
			return CartSnapshot.unreadable(response.status());
		}
		try {
			JsonNode items = objectMapper.readTree(body).path("items");
			if (!items.isArray()) {
				return new CartSnapshot(true, response.status(), List.of());
			}
			var cartItems = new ArrayList<CartItem>();
			for (JsonNode item : items) {
				String cartItemKey = item.path("cartItemKey").asString("").trim();
				String configSku = item.path("configSku").asString("").trim();
				String simpleSku = item.path("simpleSku").asString("").trim();
				if (!cartItemKey.isBlank() && !configSku.isBlank()) {
					cartItems.add(new CartItem(cartItemKey, configSku, simpleSku));
				}
			}
			return new CartSnapshot(true, response.status(), cartItems);
		}
		catch (Exception e) {
			log.warn("Cart API response could not be parsed: {}", e.getMessage());
			return CartSnapshot.unreadable(response.status());
		}
	}

	boolean removeItem(Page page, CartItem cartItem) {
		String url = cartApiUrl + "/items/" + URLEncoder.encode(cartItem.cartItemKey(), StandardCharsets.UTF_8);
		var response = http.delete(page, url);
		if (response.ok()) {
			return true;
		}
		log.warn("Cart API refused to remove {}: {} ({})", cartItem.configSku(), response.describe(),
				response.bodySnippet());
		return false;
	}

	/**
	 * Posts the basket call the article page's own add-to-cart button would issue. Used
	 * only as a fallback when driving the button produced no basket write at all — the
	 * click is preferred because it also carries a trusted user gesture.
	 */
	InPageResponse addItem(Page page, String campaignId, String configSku, String simpleSku) {
		var payload = new LinkedHashMap<String, Object>();
		payload.put("campaignIdentifier", campaignId);
		payload.put("configSku", configSku);
		payload.put("simpleSku", simpleSku);
		payload.put("quantity", 1);
		payload.put("additional", java.util.Map.of("reco", "0"));
		return http.postJson(page, cartApiUrl + "/items", payload);
	}

}
