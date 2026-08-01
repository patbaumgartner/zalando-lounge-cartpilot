package com.patbaumgartner.zalando.lounge.cartpilot.adapter.out.browser;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import com.patbaumgartner.zalando.lounge.cartpilot.config.CartPilotProperties;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.Gender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@DisplayName("PlaywrightBrowserAdapter end-to-end")
class PlaywrightBrowserAdapterE2ETest {

	@TempDir
	Path tempDir;

	@Test
	@DisplayName("adds an item to the cart, exposes it through the cart API, and clears it again")
	void addsItemToCartAndClearsItAgain() throws Exception {
		try (var server = new MockCartServer();
				var playwright = Playwright.create();
				var browser = launchBrowser(playwright)) {
			var properties = cartPilotProperties(server.baseUrl(), tempDir.resolve("session.json"));
			var authService = mock(AuthenticationService.class);
			var campaignScraper = mock(CampaignScraper.class);
			var adapter = new PlaywrightBrowserAdapter(playwright, browser, authService, campaignScraper, properties,
					JsonMapper.builder().build());

			var added = adapter.addToCart(server.productUrl(), "52");
			assertThat(added.isAdded()).isTrue();
			assertThat(server.cartCount()).isEqualTo(1);
			assertThat(adapter.isItemInCart(server.productUrl())).isTrue();

			int removed = adapter.clearCart();
			assertThat(removed).isEqualTo(1);
			assertThat(server.cartCount()).isZero();
			assertThat(adapter.isItemInCart(server.productUrl())).isFalse();
		}
	}

	@Test
	@DisplayName("reads in-stock sizes from the catalog article API")
	void readsInStockSizesFromServerState() throws Exception {
		try (var server = new MockCartServer();
				var playwright = Playwright.create();
				var browser = launchBrowser(playwright)) {
			var properties = cartPilotProperties(server.baseUrl(), tempDir.resolve("session.json"));
			var authService = mock(AuthenticationService.class);
			var campaignScraper = mock(CampaignScraper.class);
			var adapter = new PlaywrightBrowserAdapter(playwright, browser, authService, campaignScraper, properties,
					JsonMapper.builder().build());

			var details = adapter.fetchProductDetails(server.productUrl());

			// The sold-out size (54) is excluded; only AVAILABLE simples are returned.
			assertThat(details.sizes()).containsExactly("48", "50", "52");
			// Gender is read from the article's "gender" array (["male"] → MEN).
			assertThat(details.gender()).isEqualTo(Gender.MEN);
		}
	}

	@Test
	@DisplayName("refreshes a cart hold by removing and re-adding the item")
	void refreshesCartHoldByRemovingAndReAdding() throws Exception {
		try (var server = new MockCartServer();
				var playwright = Playwright.create();
				var browser = launchBrowser(playwright)) {
			var properties = cartPilotProperties(server.baseUrl(), tempDir.resolve("session.json"));
			var authService = mock(AuthenticationService.class);
			var campaignScraper = mock(CampaignScraper.class);
			var adapter = new PlaywrightBrowserAdapter(playwright, browser, authService, campaignScraper, properties,
					JsonMapper.builder().build());

			assertThat(adapter.addToCart(server.productUrl(), "52").isAdded()).isTrue();
			assertThat(server.removeCount()).isZero();
			assertThat(server.addCount()).isEqualTo(1);

			var refreshed = adapter.refreshCartItem(server.productUrl(), "52");

			assertThat(refreshed.isAdded()).isTrue();
			// The refresh issued exactly one DELETE and one re-add against the basket.
			assertThat(server.removeCount()).isEqualTo(1);
			assertThat(server.addCount()).isEqualTo(2);
			assertThat(server.cartCount()).isEqualTo(1);
			assertThat(adapter.isItemInCart(server.productUrl())).isTrue();
		}
	}

	@Test
	@DisplayName("removes a matching item through the cart API")
	void removesMatchingItemThroughCartApi() throws Exception {
		try (var server = new MockCartServer();
				var playwright = Playwright.create();
				var browser = launchBrowser(playwright)) {
			var properties = cartPilotProperties(server.baseUrl(), tempDir.resolve("session.json"));
			var authService = mock(AuthenticationService.class);
			var campaignScraper = mock(CampaignScraper.class);
			var adapter = new PlaywrightBrowserAdapter(playwright, browser, authService, campaignScraper, properties,
					JsonMapper.builder().build());

			assertThat(adapter.addToCart(server.productUrl(), "52").isAdded()).isTrue();

			adapter.removeFromCart(server.productUrl());

			assertThat(server.cartCount()).isZero();
			assertThat(adapter.isItemInCart(server.productUrl())).isFalse();
		}
	}

	private Browser launchBrowser(Playwright playwright) {
		return playwright.chromium()
			.launch(new BrowserType.LaunchOptions().setHeadless(true)
				.setArgs(List.of("--no-sandbox", "--disable-setuid-sandbox", "--disable-dev-shm-usage",
						"--disable-http2")));
	}

	private CartPilotProperties cartPilotProperties(String baseUrl, Path sessionFile) {
		var zalando = new CartPilotProperties.ZalandoProperties("test@example.com", "test", sessionFile.toString(),
				baseUrl, baseUrl + "/event", 1, 2, 30_000, 5_000, 1, true, false, 30_000, false, 5_000, 30_000, 30_000,
				1_000, 0, false, tempDir.resolve("diagnostics").toString(), "");
		var telegram = new CartPilotProperties.TelegramProperties("test-token", "-1001234567890");
		var cart = new CartPilotProperties.CartProperties(20, 15, 2);
		var scheduler = new CartPilotProperties.SchedulerProperties("0 0 6 * * *", "0 10 6 * * *", "0 */15 * * * *",
				"Europe/Zurich");
		return new CartPilotProperties(zalando, telegram, cart, scheduler);
	}

	private static final class MockCartServer implements AutoCloseable {

		private final HttpServer server;

		private final AtomicInteger cartCount = new AtomicInteger();

		private final AtomicInteger addCount = new AtomicInteger();

		private final AtomicInteger removeCount = new AtomicInteger();

		private final String baseUrl;

		private final String productUrl;

		private static final String ARTICLE_ID = "MAMMUT123-K11";

		private static final String CART_ITEM_KEY = "mock-cart-item-key";

		private MockCartServer() throws IOException {
			this.server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
			int port = server.getAddress().getPort();
			this.baseUrl = "http://localhost:" + port;
			this.productUrl = baseUrl + "/campaigns/MOCK/articles/" + ARTICLE_ID;

			server.createContext("/campaigns/MOCK/articles/" + ARTICLE_ID, this::handleProductPage);
			server.createContext("/api/phoenix/catalog/events/MOCK/articles/" + ARTICLE_ID,
					this::handleArticleDetailApi);
			server.createContext("/cart", this::handleCartPage);
			server.createContext("/api/phoenix/stockcart/cart", this::handleCartApi);
			server.createContext("/api/phoenix/stockcart/cart/items", this::handleCartItemsApi);
			server.createContext("/api/cart/add", this::handleAddToCart);
			server.createContext("/", this::handleRoot);
			server.start();
		}

		String baseUrl() {
			return baseUrl;
		}

		String productUrl() {
			return productUrl;
		}

		int cartCount() {
			return cartCount.get();
		}

		int addCount() {
			return addCount.get();
		}

		int removeCount() {
			return removeCount.get();
		}

		@Override
		public void close() {
			server.stop(0);
		}

		private void handleProductPage(HttpExchange exchange) throws IOException {
			respondHtml(exchange, 200,
					"""
							<!doctype html>
							<html>
							<head><title>Mock Product</title></head>
							<body>
							  <main>
							    <h1>Convey Tour 45</h1>
							    <fieldset aria-label="Size">
							      <input type="radio" name="size" data-testid="article-size-toggle" id="size-48">
							      <label for="size-48">48</label>
							      <input type="radio" name="size" data-testid="article-size-toggle" id="size-50">
							      <label for="size-50">50</label>
							      <input type="radio" name="size" data-testid="article-size-toggle" id="size-52">
							      <label for="size-52">52</label>
							      <input type="radio" name="size" data-testid="article-size-toggle" id="size-54">
							      <label for="size-54">54</label>
							    </fieldset>
							    <button data-testid="add-to-cart" class="auto-tests-add-to-cart-button" id="add-to-cart">Add to cart</button>
							    <script>
							      window.__INITIAL_STATE__ = {"articleDetails":{"article":{"configSku":"MAMMUT123-K11","genders":["male"],"simples":[
							        {"sku":"MAMMUT123-K110048000","size":"48","stockStatus":"AVAILABLE"},
							        {"sku":"MAMMUT123-K110050000","size":"50","stockStatus":"AVAILABLE"},
							        {"sku":"MAMMUT123-K110052000","size":"52","stockStatus":"AVAILABLE"},
							        {"sku":"MAMMUT123-K110054000","size":"54","stockStatus":"SOLD_OUT"}
							      ]}}};
							      document.getElementById('add-to-cart').addEventListener('click', async () => {
							        await fetch('/api/cart/add', { method: 'POST' });
							        window.location.href = '/cart';
							      });
							    </script>
							  </main>
							</body>
							</html>
							""");
		}

		private void handleArticleDetailApi(HttpExchange exchange) throws IOException {
			respondJson(exchange, 200, """
					{
					  "brand": "Mammut",
					  "nameCategoryTag": "Convey Tour Jacke",
					  "gender": ["male"],
					  "price": 45000,
					  "specialPrice": 22500,
					  "savings": 50,
					  "sku": "MAMMUT123-K11",
					  "stockStatus": "AVAILABLE",
					  "simples": [
					    {"filterValue": "48", "stockStatus": "AVAILABLE", "sku": "MAMMUT123-K110048000"},
					    {"filterValue": "50", "stockStatus": "AVAILABLE", "sku": "MAMMUT123-K110050000"},
					    {"filterValue": "52", "stockStatus": "AVAILABLE", "sku": "MAMMUT123-K110052000"},
					    {"filterValue": "54", "stockStatus": "SOLD_OUT", "sku": "MAMMUT123-K110054000"}
					  ],
					  "urlPath": {"40": "/campaigns/MOCK/categories/1/articles/MAMMUT123-K11"}
					}
					""");
		}

		private void handleCartPage(HttpExchange exchange) throws IOException {
			respondHtml(exchange, 200, """
					<!doctype html>
					<html>
					<head><title>Mock Cart</title></head>
					<body>
					  <header>
					    <span data-testid="cart-count" class="cart-badge">%d</span>
					  </header>
					  <section>
					    <p>Recommendation carousel placeholder.</p>
					  </section>
					</body>
					</html>
					""".formatted(cartCount.get()));
		}

		private void handleRoot(HttpExchange exchange) throws IOException {
			respondHtml(exchange, 200,
					"<!doctype html><html><head><title>Mock Home</title></head><body></body></html>");
		}

		private void handleCartApi(HttpExchange exchange) throws IOException {
			String json = (cartCount.get() > 0)
					? "{\"items\":[{\"cartItemKey\":\"" + CART_ITEM_KEY + "\",\"configSku\":\"" + ARTICLE_ID + "\"}]}"
					: "{\"items\":[]}";
			respondJson(exchange, 200, json);
		}

		private void handleAddToCart(HttpExchange exchange) throws IOException {
			addCount.incrementAndGet();
			cartCount.set(1);
			respondJson(exchange, 200, "{\"count\":1}");
		}

		private void handleCartItemsApi(HttpExchange exchange) throws IOException {
			String method = exchange.getRequestMethod();
			if ("POST".equals(method)) {
				addCount.incrementAndGet();
				cartCount.set(1);
				respondJson(exchange, 200, "{\"cartItemKey\":\"" + CART_ITEM_KEY + "\"}");
				return;
			}
			if ("DELETE".equals(method) && exchange.getRequestURI().getPath().endsWith("/" + CART_ITEM_KEY)) {
				removeCount.incrementAndGet();
				cartCount.set(0);
				exchange.sendResponseHeaders(204, -1);
				return;
			}
			respondJson(exchange, 404, "{\"error\":\"not_found\"}");
		}

		private void respondHtml(HttpExchange exchange, int status, String html) throws IOException {
			byte[] body = html.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
			exchange.sendResponseHeaders(status, body.length);
			try (OutputStream output = exchange.getResponseBody()) {
				output.write(body);
			}
		}

		private void respondJson(HttpExchange exchange, int status, String json) throws IOException {
			byte[] body = json.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
			exchange.sendResponseHeaders(status, body.length);
			try (OutputStream output = exchange.getResponseBody()) {
				output.write(body);
			}
		}

	}

}
