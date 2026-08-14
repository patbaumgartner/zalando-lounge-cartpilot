package com.patbaumgartner.zalando.lounge.cartpilot.adapter.out.browser;

import com.microsoft.playwright.Playwright;
import com.patbaumgartner.zalando.lounge.cartpilot.config.CartPilotProperties;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.DiscoveredProduct;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.core.env.StandardEnvironment;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Live smoke test against the real Zalando Lounge site. Disabled by default; only runs
 * when {@code -Dlive.cart.test=true} is passed, so it is never part of {@code verify}.
 *
 * Requires:
 * <ul>
 * <li>A running Patchright server (default {@code ws://localhost:3000/cartpilot},
 * override with {@code -Dpatchright.ws=...}).</li>
 * <li>A valid logged-in {@code session/state.json} in the working directory.</li>
 * </ul>
 *
 * It proves two behaviours end-to-end against production:
 * <ol>
 * <li><b>Cart keep-alive</b> \u2014 {@code refreshCartItem} removes and re-adds the item,
 * and the item remains in the authoritative cart afterwards (the basket mutation that
 * resets Zalando's server-side reservation timer).</li>
 * <li><b>Cart clearing</b> \u2014 {@code clearCart} empties the basket via the stockcart
 * API.</li>
 * </ol>
 */
@DisplayName("PlaywrightBrowserAdapter live (real Zalando)")
@EnabledIfSystemProperty(named = "live.cart.test", matches = "true")
class PlaywrightBrowserAdapterLiveTest {

	private static final String BASE_URL = "https://www.zalando-lounge.ch";

	@Test
	@DisplayName("adds, keep-alive refreshes, and clears a real in-stock article")
	void liveAddRefreshClear() {
		String ws = System.getProperty("patchright.ws", "ws://localhost:3000/cartpilot");
		var properties = liveProperties(ws);

		try (var playwright = Playwright.create(); var browser = playwright.chromium().connect(ws)) {
			var environment = new StandardEnvironment();
			var authService = new AuthenticationService(properties, environment);
			var campaignScraper = new CampaignScraper(JsonMapper.builder().build(), authService);
			var adapter = new PlaywrightBrowserAdapter(playwright, browser, authService, campaignScraper, properties,
					JsonMapper.builder().build());

			log("Ensuring an authenticated session (reuses session/state.json, logs in otherwise)...");
			adapter.ensureAuthenticated();

			log("Discovering a currently in-stock article from today's campaigns...");
			var inStock = findInStockArticle(adapter);
			if (inStock == null) {
				assumeTrue(false, "No in-stock article found in today's campaigns — cannot run live test");
				return;
			}

			String productUrl = inStock.productUrl();
			String size = inStock.size();
			log("Using article: " + productUrl + " (size " + size + ")");

			// Start from a clean basket so counts are unambiguous.
			adapter.clearCart();
			assertThat(adapter.isItemInCart(productUrl)).as("cart empty before test").isFalse();

			// 1) Add to cart.
			Instant addedAt = Instant.now();
			var added = adapter.addToCart(productUrl, size);
			log("addToCart -> " + added.describe());
			assumeTrue(added.isAdded(), "Could not add the article (sold out between scan and add) \u2014 skipping");
			assertThat(adapter.isItemInCart(productUrl)).as("item present right after add").isTrue();

			// 2) Keep-alive refresh: remove + re-add. This is the basket mutation that
			// resets Zalando's server-side reservation timer.
			var refreshed = adapter.refreshCartItem(productUrl, size);
			Duration sinceAdd = Duration.between(addedAt, Instant.now());
			log("refreshCartItem -> " + refreshed.describe() + " (" + sinceAdd.toSeconds() + "s after add)");
			assertThat(refreshed.isRefreshed()).as("item re-added during keep-alive refresh").isTrue();
			assertThat(adapter.isItemInCart(productUrl)).as("item still present after refresh").isTrue();

			// 3) Clear the cart via the stockcart API.
			var cleared = adapter.clearCart();
			log("clearCart -> " + cleared.describe());
			assertThat(cleared.cartReadable()).as("basket readable during clear").isTrue();
			assertThat(cleared.removedCount()).as("clearCart removed at least one item").isGreaterThanOrEqualTo(1);
			assertThat(adapter.isItemInCart(productUrl)).as("cart empty after clear").isFalse();

			log("LIVE RESULT: keep-alive refresh OK, cart clearing OK.");
		}
	}

	/**
	 * Walks today's campaigns and returns the first product that has at least one
	 * selectable size, paired with that size. Returns {@code null} when nothing in stock
	 * is found within a bounded search.
	 */
	private InStockArticle findInStockArticle(PlaywrightBrowserAdapter adapter) {
		var campaigns = adapter.fetchTodayCampaigns();
		log("Open campaigns today: " + campaigns.size());
		int checked = 0;
		for (var campaign : campaigns) {
			List<DiscoveredProduct> products;
			try {
				products = adapter.scrapeProducts(campaign);
			}
			catch (Exception e) {
				log("scrapeProducts failed for a campaign: " + e.getMessage());
				continue;
			}
			for (var product : products) {
				if (checked++ >= 25) {
					return null;
				}
				var details = adapter.fetchProductDetails(product.productUrl());
				if (!details.sizes().isEmpty()) {
					return new InStockArticle(product.productUrl(), details.sizes().get(0));
				}
			}
		}
		return null;
	}

	private CartPilotProperties liveProperties(String ws) {
		String email = System.getenv().getOrDefault("ZALANDO_EMAIL", "");
		String password = System.getenv().getOrDefault("ZALANDO_PASSWORD", "");
		var zalando = new CartPilotProperties.ZalandoProperties(email, password, "session/state.json", BASE_URL,
				BASE_URL + "/event", 1, 2, 30_000, 30_000, 3, true, false, 30_000, false, 8_000, 30_000, 30_000, 1_000,
				0, true, "target/diagnostics", ws);
		var telegram = new CartPilotProperties.TelegramProperties("unused", "unused");
		var cart = new CartPilotProperties.CartProperties(20, 15, 2);
		var scheduler = new CartPilotProperties.SchedulerProperties("0 0 6 * * *", "0 10 6 * * *", "0 */15 * * * *",
				"Europe/Zurich");
		return new CartPilotProperties(zalando, telegram, cart, scheduler);
	}

	private static void log(String message) {
		System.out.println("[live-cart-test] " + message);
	}

	private record InStockArticle(String productUrl, String size) {
	}

}
