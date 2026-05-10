package com.patbaumgartner.zalando.lounge.cartpilot.adapter.out.browser;

import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Browser.NewContextOptions;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.ColorScheme;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitUntilState;
import com.patbaumgartner.zalando.lounge.cartpilot.config.CartPilotProperties;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.Campaign;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.DiscoveredProduct;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.Gender;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.ProductDetails;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.port.out.BrowserPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Playwright-backed implementation of {@link BrowserPort}.
 *
 * Uses a single {@link BrowserContext} (with its own cookie jar) throughout the
 * application lifetime; each operation opens a fresh {@link Page} and closes it when
 * done.
 */
@Component
@Profile("!test")
public class PlaywrightBrowserAdapter implements BrowserPort {

	private static final Logger log = LoggerFactory.getLogger(PlaywrightBrowserAdapter.class);

	private static final String ADD_TO_CART_BTN = "button.auto-tests-add-to-cart-button";

	private static final String SIZE_OPTION_SELECTOR = "input[name='size'][data-testid='article-size-toggle']";

	/**
	 * Marker that precedes the server-rendered Redux state blob in every article
	 * document. The article's size/stock data lives under
	 * {@code articleDetails.article.simples}; reading it from this blob avoids rendering
	 * (and hydrating) the whole single-page app just to learn which sizes are in stock.
	 */
	private static final String INITIAL_STATE_MARKER = "__INITIAL_STATE__";

	/**
	 * How many times to retry the article document request when the site answers
	 * {@code 429 Too Many Requests}. The rapid sequential size scan occasionally trips
	 * Akamai's rate limiter; a short jittered backoff clears it while keeping the fast
	 * server-state path instead of falling back to a full page render.
	 */
	private static final int SIZE_API_RATE_LIMIT_RETRIES = 4;

	/**
	 * Base backoff in milliseconds for {@code 429} retries (doubled each attempt).
	 */
	private static final long SIZE_API_RETRY_BACKOFF_MS = 750;

	/**
	 * Minimum spacing between consecutive article-document requests. Without pacing the
	 * scan fires ~2.3 requests/second, which trips Akamai's rate limiter on roughly a
	 * third of fetches; each {@code 429} then costs 0.75-6 s of backoff (or a slow page
	 * render fallback). Throttling to well under that threshold keeps the fast path fast
	 * and avoids the retry/render penalties, so the whole scan finishes sooner.
	 */
	private static final long SIZE_API_MIN_INTERVAL_MS = 600;

	private static final java.util.regex.Pattern ARTICLE_ID_PATTERN = java.util.regex.Pattern
		.compile("/articles/([^/?#]+)");

	private final Playwright playwright;

	private final AuthenticationService authService;

	private final CampaignScraper campaignScraper;

	private final CartPilotProperties properties;

	private final ObjectMapper objectMapper;

	private Browser browser;

	private BrowserContext context;

	private boolean cookieConsentHandled;

	private long lastApiRequestAtNanos;

	private record CartItem(String cartItemKey, String configSku) {
	}

	public PlaywrightBrowserAdapter(Playwright playwright, Browser browser, AuthenticationService authService,
			CampaignScraper campaignScraper, CartPilotProperties properties, ObjectMapper objectMapper) {
		this.playwright = playwright;
		this.browser = browser;
		this.authService = authService;
		this.campaignScraper = campaignScraper;
		this.properties = properties;
		this.objectMapper = objectMapper;
	}

	@Override
	public synchronized void ensureAuthenticated() {
		ensureConnected();

		int maxResets = authService.authContextResetRetries();
		for (int attempt = 0; attempt <= maxResets; attempt++) {
			try {
				authService.ensureAuthenticated(context);
				return;
			}
			catch (Exception e) {
				boolean canReset = attempt < maxResets && authService.shouldResetContext(e);
				if (!canReset) {
					throw e;
				}

				log.warn("Authentication failed (attempt {}/{}), recreating browser context: {}", attempt + 1,
						maxResets + 1, e.getMessage());
				resetContext();
			}
		}
	}

	@Override
	public List<Campaign> fetchTodayCampaigns() {
		try (var page = openPage()) {
			return campaignScraper.scrapeOpenCampaigns(page, properties.zalando().campaignUrl());
		}
	}

	@Override
	public List<DiscoveredProduct> scrapeProducts(Campaign campaign) {
		try (var page = openPage()) {
			return campaignScraper.scrapeProducts(page, campaign);
		}
	}

	@Override
	public ProductDetails fetchProductDetails(String productUrl) {
		try {
			ProductDetails apiDetails = fetchProductDetailsViaApi(productUrl);
			if (apiDetails != null) {
				log.info("Detail on {}: gender={}, sizes={}", productUrl, apiDetails.gender(), apiDetails.sizes());
				return apiDetails;
			}

			log.debug("Server-rendered article state unavailable for {}; falling back to page render", productUrl);
			try (var page = openPage()) {
				return new ProductDetails(fetchAvailableSizesViaRender(page, productUrl), Gender.UNISEX);
			}
		}
		catch (Exception e) {
			log.error("fetchProductDetails failed for {}: {}", productUrl, e.getMessage(), e);
			return ProductDetails.empty();
		}
	}

	/**
	 * Reads the article's enrichment (sizes + gender) straight from the document's
	 * server-rendered state instead of navigating and waiting for the single-page app to
	 * hydrate. A single authenticated {@code GET} returns HTML containing
	 * {@code window.__INITIAL_STATE__}, whose {@code articleDetails.article} node lists
	 * every size with its {@code stockStatus} and the {@code genders} array that names
	 * the target group (gender).
	 * @return the parsed sizes (possibly empty when every size is sold out) and resolved
	 * gender, or {@code null} when the state blob could not be located/parsed so the
	 * caller can fall back to rendering.
	 */
	private ProductDetails fetchProductDetailsViaApi(String productUrl) {
		var request = connectedRequestContext();
		try {
			for (int attempt = 0; attempt <= SIZE_API_RATE_LIMIT_RETRIES; attempt++) {
				paceApiRequest();
				var response = request.get(productUrl);
				try {
					if (response.status() == 429) {
						if (attempt == SIZE_API_RATE_LIMIT_RETRIES) {
							log.warn("Article request still rate-limited (429) after {} retries for {}",
									SIZE_API_RATE_LIMIT_RETRIES, productUrl);
							return null;
						}
						long backoff = SIZE_API_RETRY_BACKOFF_MS * (1L << attempt);
						log.debug("Rate-limited (429) on {}; retrying in {} ms (attempt {}/{})", productUrl, backoff,
								attempt + 1, SIZE_API_RATE_LIMIT_RETRIES);
						Thread.sleep(backoff);
						continue;
					}
					if (!response.ok()) {
						log.warn("Article request returned status {} for {}", response.status(), productUrl);
						return null;
					}

					JsonNode article = extractArticle(response.text());
					if (article == null) {
						return null;
					}

					JsonNode simples = article.path("simples");
					if (!simples.isArray()) {
						return null;
					}

					var sizes = new ArrayList<String>();
					for (JsonNode simple : simples) {
						if ("AVAILABLE".equals(simple.path("stockStatus").asString(""))) {
							String size = simple.path("size").asString("").trim();
							if (!size.isBlank()) {
								sizes.add(size);
							}
						}
					}
					return new ProductDetails(sizes, detectGender(article));
				}
				finally {
					// Free the buffered response body in the Playwright driver; otherwise
					// undisposed responses accumulate and eventually kill the driver pipe
					// over a full scan of several hundred articles.
					response.dispose();
				}
			}
			return null;
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return null;
		}
		catch (Exception e) {
			log.debug("Could not read article state for {}: {}", productUrl, e.getMessage());
			return null;
		}
	}

	/**
	 * Extracts the {@code articleDetails.article} node from an article document's
	 * embedded {@code window.__INITIAL_STATE__} blob, or {@code null} when the marker is
	 * missing or the JSON cannot be parsed into the expected shape.
	 */
	private JsonNode extractArticle(String html) {
		int marker = html.indexOf(INITIAL_STATE_MARKER);
		if (marker < 0) {
			return null;
		}
		int start = html.indexOf('{', marker);
		if (start < 0) {
			return null;
		}
		int end = matchClosingBrace(html, start);
		if (end < 0) {
			return null;
		}

		try {
			JsonNode state = objectMapper.readTree(html.substring(start, end));
			JsonNode article = state.path("articleDetails").path("article");
			return article.isObject() ? article : null;
		}
		catch (Exception e) {
			log.debug("Failed to parse server-rendered article state: {}", e.getMessage());
			return null;
		}
	}

	/**
	 * Resolves the article's gender from its server-rendered {@code genders} array (e.g.
	 * {@code ["male"]} → MEN, {@code ["female"]} → WOMEN). Mixed or unknown values fall
	 * back to {@link Gender#UNISEX} so the gender gate never over-filters.
	 */
	private static Gender detectGender(JsonNode article) {
		JsonNode genders = article.path("genders");
		if (!genders.isArray() || genders.isEmpty()) {
			return Gender.UNISEX;
		}
		boolean male = false;
		boolean female = false;
		boolean kids = false;
		for (JsonNode g : genders) {
			switch (g.asString("").trim().toLowerCase(java.util.Locale.ROOT)) {
				case "male", "men", "man" -> male = true;
				case "female", "women", "woman" -> female = true;
				case "boy", "girl", "kid", "kids", "baby", "junior", "children" -> kids = true;
				default -> {
					// unknown/unisex token contributes no vote
				}
			}
		}
		if (kids && !male && !female) {
			return Gender.KIDS;
		}
		if (male && !female) {
			return Gender.MEN;
		}
		if (female && !male) {
			return Gender.WOMEN;
		}
		return Gender.UNISEX;
	}

	/**
	 * Returns the index just past the brace that closes the JSON object opened at
	 * {@code start}, honouring quoted strings and escapes, or {@code -1} when unbalanced.
	 */
	private static int matchClosingBrace(String s, int start) {
		int depth = 0;
		boolean inString = false;
		boolean escaped = false;
		for (int i = start; i < s.length(); i++) {
			char c = s.charAt(i);
			if (escaped) {
				escaped = false;
				continue;
			}
			if (c == '\\') {
				escaped = true;
				continue;
			}
			if (c == '"') {
				inString = !inString;
				continue;
			}
			if (inString) {
				continue;
			}
			if (c == '{') {
				depth++;
			}
			else if (c == '}') {
				depth--;
				if (depth == 0) {
					return i + 1;
				}
			}
		}
		return -1;
	}

	/**
	 * Legacy fallback that renders the article page and reads the selectable size radios.
	 * Used only when the server-rendered state blob is absent or unparseable.
	 */
	private List<String> fetchAvailableSizesViaRender(Page page, String productUrl) {
		page.navigate(productUrl, new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
		page.waitForLoadState(LoadState.DOMCONTENTLOADED);
		acceptCookieBannerOnce(page);

		var sizeInputs = page.locator(SIZE_OPTION_SELECTOR);
		try {
			sizeInputs.first().waitFor(new Locator.WaitForOptions().setTimeout(10_000));
		}
		catch (Exception e) {
			log.warn("No size options rendered on {}", productUrl);
			return List.of();
		}

		var sizes = readSelectableSizes(page, sizeInputs);
		log.info("Available sizes on {}: {}", productUrl, sizes);
		return sizes;
	}

	@Override
	public boolean addToCart(String productUrl, String size) {
		try (var page = openPage()) {
			page.navigate(productUrl);
			page.waitForLoadState(LoadState.DOMCONTENTLOADED);
			acceptCookieBannerOnce(page);

			// Wait for the size options to render (SPA hydration).
			var sizeInputs = page.locator(SIZE_OPTION_SELECTOR);
			try {
				sizeInputs.first().waitFor(new Locator.WaitForOptions().setTimeout(10_000));
			}
			catch (Exception e) {
				log.warn("No size options rendered on {}", productUrl);
				return false;
			}

			String requested = (size == null) ? null : size.trim();
			String selectedSize = selectSize(page, sizeInputs, requested);
			if (selectedSize == null) {
				log.warn("No selectable size found on {} (requested='{}')", productUrl, size);
				return false;
			}
			log.info("Selected size '{}' on {}", selectedSize, productUrl);

			var addButton = page.locator(ADD_TO_CART_BTN).first();
			try {
				addButton.waitFor(new Locator.WaitForOptions().setTimeout(5_000));
			}
			catch (Exception e) {
				log.warn("Add-to-cart button not found on {}", productUrl);
				return false;
			}
			addButton.click();

			// The "In den Warenkorb" button text does not change and the SPA never goes
			// network-idle, so confirm the add against the cart API (the authoritative
			// basket) rather than scraping the DOM. Poll briefly while the add-to-cart
			// XHR settles.
			String articleId = articleId(productUrl);
			boolean inCart = false;
			for (int attempt = 0; attempt < 6; attempt++) {
				page.waitForTimeout(500);
				if (isInCartViaApi(page, articleId)) {
					inCart = true;
					break;
				}
			}
			log.info("addToCart result for {} (size {}): inCart={}", productUrl, selectedSize, inCart);
			return inCart;
		}
		catch (Exception e) {
			log.error("addToCart failed for {}: {}", productUrl, e.getMessage(), e);
			return false;
		}
	}

	/**
	 * Selects an available size on an article detail page. When {@code requested} is
	 * blank, the first selectable (non-disabled) size is chosen. Returns the size label
	 * that was selected, or {@code null} when no match is available.
	 */
	private String selectSize(Page page, Locator sizeInputs, String requested) {
		int count = sizeInputs.count();
		for (int i = 0; i < count; i++) {
			var input = sizeInputs.nth(i);
			if (Boolean.TRUE.equals(input.isDisabled())) {
				continue;
			}
			String id = input.getAttribute("id");
			if (id == null || id.isBlank()) {
				continue;
			}
			var label = page.locator("label[for='%s']".formatted(id));
			if (label.count() == 0) {
				continue;
			}
			String optionSize = label.first().innerText().trim().split("\\R")[0].trim();
			if (requested == null || requested.isBlank() || optionSize.equalsIgnoreCase(requested)) {
				label.first().click();
				return optionSize;
			}
		}
		return null;
	}

	/**
	 * Returns the selectable (non-disabled) size labels rendered on an article detail
	 * page.
	 */
	private List<String> readSelectableSizes(Page page, Locator sizeInputs) {
		var sizes = new ArrayList<String>();
		int count = sizeInputs.count();
		for (int i = 0; i < count; i++) {
			var input = sizeInputs.nth(i);
			if (Boolean.TRUE.equals(input.isDisabled())) {
				continue;
			}
			String id = input.getAttribute("id");
			if (id == null || id.isBlank()) {
				continue;
			}
			var label = page.locator("label[for='%s']".formatted(id));
			if (label.count() == 0) {
				continue;
			}
			String optionSize = label.first().innerText().trim().split("\\R")[0].trim();
			if (!optionSize.isBlank()) {
				sizes.add(optionSize);
			}
		}
		return sizes;
	}

	@Override
	public void removeFromCart(String productUrl) {
		try {
			String articleId = articleId(productUrl);
			if (articleId == null || articleId.isBlank()) {
				log.warn("Could not derive article id for cart removal: {}", productUrl);
				return;
			}

			var request = connectedRequestContext();
			boolean removed = false;
			for (var cartItem : fetchCartItems(request)) {
				if (articleId.equals(cartItem.configSku())) {
					removed |= removeCartItemViaApi(request, cartItem);
				}
			}

			if (!removed) {
				log.warn("No cart API item found for {}", productUrl);
			}
		}
		catch (Exception e) {
			log.error("removeFromCart failed for {}: {}", productUrl, e.getMessage(), e);
		}
	}

	@Override
	public boolean isItemInCart(String productUrl) {
		try (var page = openPage()) {
			// The cart API call needs the site's cookies/origin; land on the base URL
			// first, then query the authoritative basket endpoint.
			page.navigate(properties.zalando().baseUrl());
			page.waitForLoadState(LoadState.DOMCONTENTLOADED);
			return isInCartViaApi(page, articleId(productUrl));
		}
		catch (Exception e) {
			log.error("isItemInCart failed for {}: {}", productUrl, e.getMessage(), e);
			return false;
		}
	}

	/**
	 * Checks the authoritative cart API for the given article. The {@code /cart} page
	 * only renders recommendation carousels; the basket itself is exposed as JSON at
	 * {@link CartPilotProperties.ZalandoProperties#cartApiUrl()} where each line item
	 * carries a {@code configSku} equal to the article id (e.g. {@code JA222E1K2-K11}).
	 */
	private boolean isInCartViaApi(Page page, String articleId) {
		if (articleId == null || articleId.isBlank()) {
			return false;
		}
		return fetchCartItems(page.request()).stream().anyMatch(cartItem -> articleId.equals(cartItem.configSku()));
	}

	private List<CartItem> fetchCartItems(APIRequestContext request) {
		try {
			var response = request.get(properties.zalando().cartApiUrl());
			try {
				if (!response.ok()) {
					log.warn("Cart API returned status {} when reading basket", response.status());
					return List.of();
				}
				String body = response.text();
				if (body.isBlank()) {
					return List.of();
				}

				JsonNode items = objectMapper.readTree(body).path("items");
				if (!items.isArray()) {
					return List.of();
				}

				var cartItems = new ArrayList<CartItem>();
				for (JsonNode item : items) {
					String cartItemKey = item.path("cartItemKey").asString("").trim();
					String configSku = item.path("configSku").asString("").trim();
					if (!cartItemKey.isBlank() && !configSku.isBlank()) {
						cartItems.add(new CartItem(cartItemKey, configSku));
					}
				}
				return cartItems;
			}
			finally {
				response.dispose();
			}
		}
		catch (Exception e) {
			log.warn("Cart API read failed: {}", e.getMessage());
			return List.of();
		}
	}

	private boolean removeCartItemViaApi(APIRequestContext request, CartItem cartItem) {
		String url = properties.zalando().cartApiUrl() + "/items/"
				+ URLEncoder.encode(cartItem.cartItemKey(), StandardCharsets.UTF_8);
		try {
			var response = request.delete(url);
			try {
				if (response.ok()) {
					return true;
				}
				log.warn("Cart API returned status {} when removing {}", response.status(), cartItem.configSku());
				return false;
			}
			finally {
				response.dispose();
			}
		}
		catch (Exception e) {
			log.warn("Cart API remove failed for {}: {}", cartItem.configSku(), e.getMessage());
			return false;
		}
	}

	private static String articleId(String productUrl) {
		if (productUrl == null) {
			return null;
		}
		var matcher = ARTICLE_ID_PATTERN.matcher(productUrl);
		return matcher.find() ? matcher.group(1) : null;
	}

	@Override
	public int clearCart() {
		try {
			var request = connectedRequestContext();
			int removed = 0;
			for (var cartItem : fetchCartItems(request)) {
				if (removeCartItemViaApi(request, cartItem)) {
					removed++;
				}
			}

			log.info("Cleared {} item(s) from browser cart", removed);
			return removed;
		}
		catch (Exception e) {
			log.error("clearCart failed: {}", e.getMessage(), e);
			return 0;
		}
	}

	// ── Private helpers ────────────────────────────────────────

	private synchronized Page openPage() {
		ensureConnected();
		return context.newPage();
	}

	/**
	 * Returns the shared context request context, reconnecting first if the long-lived
	 * Patchright connection has dropped. Reusing the context's {@link APIRequestContext}
	 * lets the size scan issue hundreds of authenticated document requests without
	 * opening (and tearing down) a {@link Page} per article, which previously churned the
	 * CDP connection until the driver died.
	 */
	private synchronized APIRequestContext connectedRequestContext() {
		ensureConnected();
		return context.request();
	}

	/**
	 * Blocks until at least {@link #SIZE_API_MIN_INTERVAL_MS} has elapsed since the
	 * previous article-document request, smoothing the burst rate so Akamai's rate
	 * limiter stays quiet. Called on the single scan thread, so no synchronization is
	 * needed.
	 */
	private void paceApiRequest() {
		long now = System.nanoTime();
		if (lastApiRequestAtNanos != 0) {
			long elapsedMs = (now - lastApiRequestAtNanos) / 1_000_000L;
			long waitMs = SIZE_API_MIN_INTERVAL_MS - elapsedMs;
			if (waitMs > 0) {
				try {
					Thread.sleep(waitMs);
				}
				catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}
		}
		lastApiRequestAtNanos = System.nanoTime();
	}

	/**
	 * Dismisses the cookie banner at most once per browser context. The consent choice is
	 * stored in the context's cookie jar, so after the first page the banner never
	 * re-appears — skipping the (otherwise per-page) 3s detection wait on every
	 * subsequent navigation.
	 */
	private void acceptCookieBannerOnce(Page page) {
		if (cookieConsentHandled) {
			return;
		}
		authService.acceptCookieBannerIfPresent(page);
		cookieConsentHandled = true;
	}

	/**
	 * Rebuilds the dead singleton {@code Browser}/{@code context} whenever the long-lived
	 * CDP websocket to the Patchright server has dropped. The sidecar closes idle
	 * connections without crashing, so every entry point must heal before use rather than
	 * trusting the connection created at startup.
	 */
	private void ensureConnected() {
		if (browser == null || !browser.isConnected()) {
			log.warn("Patchright connection lost; reconnecting");
			try {
				browser = playwright.chromium().connect(properties.zalando().browserWsEndpoint());
			}
			catch (Exception e) {
				throw new IllegalStateException("Failed to reconnect to Patchright browser server", e);
			}
			context = createContext();
		}
		else if (context == null) {
			context = createContext();
		}
	}

	private void resetContext() {
		if (context != null) {
			try {
				context.close();
			}
			catch (Exception e) {
				log.debug("Could not close previous browser context during reset: {}", e.getMessage());
			}
		}
		context = null;
		ensureConnected();
	}

	private BrowserContext createContext() {
		var sessionPath = Path.of(properties.zalando().sessionFile());
		boolean hasPersistedState = Files.exists(sessionPath) && sessionPath.toFile().length() > 0;
		var options = stealthContextOptions();
		if (hasPersistedState) {
			log.info("Creating browser context with persisted storage state: {}", sessionPath);
			options.setStorageStatePath(sessionPath);
		}
		cookieConsentHandled = false;
		return browser.newContext(options);
	}

	/**
	 * Builds a context that mimics a typical Swiss desktop user so Akamai's
	 * fingerprinting sees a coherent browser profile. The user-agent is deliberately NOT
	 * overridden: Patchright sets a genuine one matching the underlying Chromium build,
	 * and a mismatch is itself a strong bot signal.
	 */
	private NewContextOptions stealthContextOptions() {
		return new NewContextOptions().setViewportSize(1920, 1080)
			.setLocale("de-CH")
			.setTimezoneId("Europe/Zurich")
			.setColorScheme(ColorScheme.LIGHT)
			.setDeviceScaleFactor(1.0)
			.setPermissions(List.of("geolocation"))
			.setGeolocation(47.3769, 8.5417);
	}

}
