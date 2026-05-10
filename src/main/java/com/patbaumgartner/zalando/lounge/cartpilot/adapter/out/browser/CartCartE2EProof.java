package com.patbaumgartner.zalando.lounge.cartpilot.adapter.out.browser;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import com.patbaumgartner.zalando.lounge.cartpilot.config.CartPilotProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.StandardEnvironment;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runnable browser-backed proof that an item can be added to the cart and then cleared.
 *
 * Run with: ./mvnw -q -DskipTests compile exec:java
 * -Dexec.mainClass=com.patbaumgartner.zalando.lounge.cartpilot.adapter.out.browser.CartCartE2EProof
 */
public final class CartCartE2EProof {

	private static final Logger log = LoggerFactory.getLogger(CartCartE2EProof.class);

	private CartCartE2EProof() {
	}

	public static void main(String[] args) throws java.io.IOException {
		try (var server = new MockCartServer();
				var playwright = Playwright.create();
				var browser = launchBrowser(playwright)) {
			var properties = cartPilotProperties(server.baseUrl(), Path.of("target", "cart-proof-session.json"));
			var authService = new AuthenticationService(properties, new StandardEnvironment());
			var campaignScraper = new CampaignScraper(new tools.jackson.databind.ObjectMapper(), authService);
			var adapter = new PlaywrightBrowserAdapter(browser, authService, campaignScraper, properties);

			log.info("Starting cart proof against {}", server.baseUrl());
			boolean added = adapter.addToCart(server.productUrl(), "52");

			assertState(added, "Expected addToCart() to return true");
			assertState(server.cartCount() == 1, "Expected the mock cart count to be 1 after addToCart()");
			assertState(adapter.isItemInCart(server.productUrl()), "Expected item to be visible in the cart page");

			int removed = adapter.clearCart();
			assertState(removed == 1, "Expected clearCart() to remove 1 item");
			assertState(server.cartCount() == 0, "Expected the mock cart count to be 0 after clearCart()");
			assertState(!adapter.isItemInCart(server.productUrl()), "Expected item to be gone from the cart page");

			log.info("Cart proof passed: item added, observed in cart, and cleared again.");
		}
	}

	private static Browser launchBrowser(Playwright playwright) {
		return playwright.chromium()
			.launch(new BrowserType.LaunchOptions().setHeadless(true)
				.setArgs(List.of("--no-sandbox", "--disable-setuid-sandbox", "--disable-dev-shm-usage",
						"--disable-http2")));
	}

	private static CartPilotProperties cartPilotProperties(String baseUrl, Path sessionFile) {
		var zalando = new CartPilotProperties.ZalandoProperties("test@example.com", "test", sessionFile.toString(),
				baseUrl, baseUrl + "/event", 1, 2, 30_000, 5_000, 1, true, false, 30_000, false, 5_000, 30_000, 30_000,
				1_000, 0, false, Path.of("target", "cart-proof-diagnostics").toString());
		var telegram = new CartPilotProperties.TelegramProperties("test-token", "-1001234567890",
				"https://localhost:8080");
		var cart = new CartPilotProperties.CartProperties(20, 15, 2);
		var scheduler = new CartPilotProperties.SchedulerProperties("0 0 6 * * *", "0 10 6 * * *", "0 */15 * * * *",
				"Europe/Zurich");
		return new CartPilotProperties(zalando, telegram, cart, "", scheduler);
	}

	private static void assertState(boolean condition, String message) {
		if (!condition) {
			throw new IllegalStateException(message);
		}
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
