package com.patbaumgartner.zalando.lounge.cartpilot.adapter.out.browser;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import com.patbaumgartner.zalando.lounge.cartpilot.config.CartPilotProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
import static org.mockito.Mockito.when;

@DisplayName("PlaywrightBrowserAdapter end-to-end")
class PlaywrightBrowserAdapterE2ETest {

	@TempDir
	Path tempDir;

	@Test
	@DisplayName("adds an item to the cart, exposes it on the cart page, and clears it again")
	void addsItemToCartAndClearsItAgain() throws Exception {
		try (var server = new MockCartServer();
				var playwright = Playwright.create();
				var browser = launchBrowser(playwright)) {
			var properties = cartPilotProperties(server.baseUrl(), tempDir.resolve("session.json"));
			var authService = mock(AuthenticationService.class);
			var campaignScraper = mock(CampaignScraper.class);
			var adapter = new PlaywrightBrowserAdapter(browser, authService, campaignScraper, properties);

			boolean added = adapter.addToCart(server.productUrl(), "52");
			assertThat(added).isTrue();
			assertThat(server.cartCount()).isEqualTo(1);
			assertThat(adapter.isItemInCart(server.productUrl())).isTrue();

			int removed = adapter.clearCart();
			assertThat(removed).isEqualTo(1);
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
				1_000, 0, false, tempDir.resolve("diagnostics").toString());
		var telegram = new CartPilotProperties.TelegramProperties("test-token", "-1001234567890",
				"https://localhost:8080");
		var cart = new CartPilotProperties.CartProperties(20, 15, 2);
		var scheduler = new CartPilotProperties.SchedulerProperties("0 0 6 * * *", "0 10 6 * * *", "0 */15 * * * *",
				"Europe/Zurich");
		return new CartPilotProperties(zalando, telegram, cart, "", scheduler);
	}

	private static final class MockCartServer implements AutoCloseable {

		private final HttpServer server;

		private final AtomicInteger cartCount = new AtomicInteger();

		private final String baseUrl;

		private final String productUrl;

		private MockCartServer() throws IOException {
			this.server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
			int port = server.getAddress().getPort();
			this.baseUrl = "http://localhost:" + port;
			this.productUrl = baseUrl + "/product/mammut-convey-tour";

			server.createContext("/product/mammut-convey-tour", this::handleProductPage);
			server.createContext("/cart", this::handleCartPage);
			server.createContext("/api/cart/add", this::handleAddToCart);
			server.createContext("/api/cart/remove", this::handleRemoveFromCart);
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

		@Override
		public void close() {
			server.stop(0);
		}

		private void handleProductPage(HttpExchange exchange) throws IOException {
			respondHtml(exchange, 200, """
					<!doctype html>
					<html>
					<head><title>Mock Product</title></head>
					<body>
					  <main>
					    <h1>Convey Tour 45</h1>
					    <select data-testid="size-selector" class="size-select" aria-label="Size">
					      <option value="48">48</option>
					      <option value="50">50</option>
					      <option value="52">52</option>
					      <option value="54">54</option>
					    </select>
					    <button data-testid="add-to-cart" class="add-to-cart" id="add-to-cart">Add to cart</button>
					    <script>
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

		private void handleCartPage(HttpExchange exchange) throws IOException {
			if (cartCount.get() > 0) {
				respondHtml(exchange, 200, """
						<!doctype html>
						<html>
						<head><title>Mock Cart</title></head>
						<body>
						  <header>
						    <span data-testid="cart-count" class="cart-badge">%d</span>
						  </header>
						  <section>
						    <a href="%s">Convey Tour 45</a>
						    <button data-testid="remove" class="remove-btn" id="remove-item">Remove</button>
						    <script>
						      document.getElementById('remove-item').addEventListener('click', async () => {
						        await fetch('/api/cart/remove', { method: 'POST' });
						        window.location.reload();
						      });
						    </script>
						  </section>
						</body>
						</html>
						""".formatted(cartCount.get(), productUrl));
				return;
			}

			respondHtml(exchange, 200, """
					<!doctype html>
					<html>
					<head><title>Mock Cart</title></head>
					<body>
					  <header>
					    <span data-testid="cart-count" class="cart-badge">0</span>
					  </header>
					  <section>
					    <p>The cart is empty.</p>
					  </section>
					</body>
					</html>
					""");
		}

		private void handleAddToCart(HttpExchange exchange) throws IOException {
			cartCount.set(1);
			respondJson(exchange, 200, "{\"count\":1}");
		}

		private void handleRemoveFromCart(HttpExchange exchange) throws IOException {
			cartCount.set(0);
			respondJson(exchange, 200, "{\"count\":0}");
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
