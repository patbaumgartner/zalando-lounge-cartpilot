package com.patbaumgartner.zalando.lounge.cartpilot.adapter.out.browser;

import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.APIResponse;
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
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.CartAddResult;
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

	private static final String SIZE_OPTION_SELECTOR = "input[name='size'][data-testid='article-size-toggle']";

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

	private static final java.util.regex.Pattern CAMPAIGN_ID_PATTERN = java.util.regex.Pattern
		.compile("/campaigns/([^/?#]+)");

	/**
	 * Upper bound on forced Akamai sensor re-warms during a single cart-add burst. The
	 * shared {@code _abck} cookie normally clears after the first warm-up but can decay
	 * mid-run; a few on-demand re-warms recover it without letting a genuinely blocked IP
	 * spend ~10 s per item chasing a token it will never earn.
	 */
	private static final int MAX_CART_SENSOR_REWARMS = 3;

	private final Playwright playwright;

	private final AuthenticationService authService;

	private final CampaignScraper campaignScraper;

	private final CartPilotProperties properties;

	private final ObjectMapper objectMapper;

	private Browser browser;

	private BrowserContext context;

	private boolean cookieConsentHandled;

	private boolean cartSensorWarmed;

	private int cartSensorRewarms;

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
		verifyBrowserAvailable();

		// Each scan starts a fresh cart-add burst; re-arm the Akamai sensor warm-up so
		// the
		// first add of the run re-solves the shared _abck cookie.
		cartSensorWarmed = false;
		cartSensorRewarms = 0;

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
	public synchronized void verifyBrowserAvailable() {
		ensureConnected();
		// Opening (and immediately closing) a real page round-trips the CDP link and
		// proves the remote browser + context are genuinely alive; newLivePage() force-
		// reconnects and, on continued failure, throws a descriptive
		// IllegalStateException
		// naming the endpoint. This turns the "browser never established" case into an
		// explicit, actionable error before the scan touches the login flow.
		try (var page = newLivePage()) {
			log.info("Patchright browser endpoint {} is reachable and yielded a live page",
					properties.zalando().browserWsEndpoint());
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
	 * Reads the article's enrichment (available sizes + gender) from the catalog article
	 * detail API. Returns {@code null} when the article could not be loaded so the caller
	 * can fall back to rendering.
	 */
	private ProductDetails fetchProductDetailsViaApi(String productUrl) {
		JsonNode article = fetchArticle(productUrl).article();
		if (article == null) {
			return null;
		}
		var sizes = CatalogArticleSupport.availableSizes(article.path("simples"));
		return new ProductDetails(sizes, CatalogArticleSupport.resolveGender(article.path("gender")));
	}

	/**
	 * An article lookup with the HTTP status that produced it, so a 403 bot wall can be
	 * told apart from a genuinely missing article.
	 */
	private record ArticleFetch(JsonNode article, int status) {

		static ArticleFetch failed(int status) {
			return new ArticleFetch(null, status);
		}

		boolean isBotWall() {
			return status == 403;
		}
	}

	/**
	 * Fetches a single article's catalog JSON
	 * ({@code /api/phoenix/catalog/events/{campaignId}/articles/{sku}}) through the
	 * authenticated request context, retrying on 429. The returned article is
	 * {@code null} when the id could not be derived or the request failed.
	 */
	private ArticleFetch fetchArticle(String productUrl) {
		String campaignId = campaignId(productUrl);
		String sku = articleId(productUrl);
		if (campaignId == null || sku == null) {
			return ArticleFetch.failed(0);
		}
		String apiUrl = origin(productUrl) + "/api/phoenix/catalog/events/" + campaignId + "/articles/" + sku;
		var request = connectedRequestContext();
		try {
			for (int attempt = 0; attempt <= SIZE_API_RATE_LIMIT_RETRIES; attempt++) {
				paceApiRequest();
				var response = request.get(apiUrl, com.microsoft.playwright.options.RequestOptions.create()
					.setHeader("Accept", "application/json"));
				try {
					if (response.status() == 429) {
						if (attempt == SIZE_API_RATE_LIMIT_RETRIES) {
							log.warn("Article request still rate-limited (429) after {} retries for {}",
									SIZE_API_RATE_LIMIT_RETRIES, apiUrl);
							return ArticleFetch.failed(429);
						}
						long backoff = SIZE_API_RETRY_BACKOFF_MS * (1L << attempt);
						log.debug("Rate-limited (429) on {}; retrying in {} ms (attempt {}/{})", apiUrl, backoff,
								attempt + 1, SIZE_API_RATE_LIMIT_RETRIES);
						Thread.sleep(backoff);
						continue;
					}
					if (!response.ok()) {
						log.warn("Article request returned status {} for {}", response.status(), apiUrl);
						return ArticleFetch.failed(response.status());
					}
					JsonNode article = objectMapper.readTree(response.text());
					return article.isObject() ? new ArticleFetch(article, response.status())
							: ArticleFetch.failed(response.status());
				}
				finally {
					// Free the buffered response body in the Playwright driver; otherwise
					// undisposed responses accumulate and eventually kill the driver pipe
					// over a full scan of several hundred articles.
					response.dispose();
				}
			}
			return ArticleFetch.failed(0);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return ArticleFetch.failed(0);
		}
		catch (Exception e) {
			log.debug("Could not read article detail for {}: {}", apiUrl, e.getMessage());
			return ArticleFetch.failed(0);
		}
	}

	private static String campaignId(String productUrl) {
		if (productUrl == null) {
			return null;
		}
		var matcher = CAMPAIGN_ID_PATTERN.matcher(productUrl);
		return matcher.find() ? matcher.group(1) : null;
	}

	private String origin(String url) {
		try {
			var uri = java.net.URI.create(url);
			if (uri.getScheme() != null && uri.getHost() != null) {
				var origin = new StringBuilder(uri.getScheme()).append("://").append(uri.getHost());
				if (uri.getPort() > 0) {
					origin.append(':').append(uri.getPort());
				}
				return origin.toString();
			}
		}
		catch (Exception ignored) {
			// fall through to the configured base URL
		}
		return properties.zalando().baseUrl();
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
	public CartAddResult addToCart(String productUrl, String size) {
		try {
			String campaignId = campaignId(productUrl);
			String configSku = articleId(productUrl);
			if (campaignId == null || configSku == null) {
				log.warn("Could not derive campaign/article id for cart add: {}", productUrl);
				return CartAddResult.failed(0, "unrecognised product URL");
			}

			// The article's per-size stockcart sku ("simpleSku") is required by the
			// basket API; look it up from the authoritative catalog detail so a size sold
			// out since discovery is caught here rather than added blindly.
			var articleFetch = fetchArticle(productUrl);
			if (articleFetch.article() == null) {
				log.warn("Could not load article detail for cart add: {} (status {})", productUrl,
						articleFetch.status());
				return articleFetch.isBotWall()
						? CartAddResult.blocked(articleFetch.status(), "bot protection refused the article lookup")
						: CartAddResult.failed(articleFetch.status(), "article detail unavailable");
			}
			String simpleSku = simpleSkuForSize(articleFetch.article().path("simples"), size);
			if (simpleSku == null) {
				log.warn("Size '{}' not available on {} — cannot add to cart", size, productUrl);
				return CartAddResult.sizeUnavailable("size " + size + " not purchasable");
			}

			// The stockcart basket endpoint is guarded by Akamai BotManager. A POST
			// issued
			// before the context's _abck cookie is promoted to a solved state comes back
			// 403 edge_error:halt, so warm the sensor once (interactive mouse/scroll) and
			// then reuse the now-trusted shared cookie for the SPA's own POST. That is
			// far
			// more reliable than driving the article page's add-to-cart button, which
			// does
			// not register the add under a headless browser.
			ensureCartSensorWarm();

			var request = connectedRequestContext();
			var payload = new java.util.LinkedHashMap<String, Object>();
			payload.put("campaignIdentifier", campaignId);
			payload.put("configSku", configSku);
			payload.put("simpleSku", simpleSku);
			payload.put("quantity", 1);
			payload.put("additional", java.util.Map.of("reco", "0"));
			String cartAddUrl = properties.zalando().cartApiUrl() + "/items";

			int status = postCartItem(request, cartAddUrl, payload, productUrl, size);
			if (status == 403 && cartSensorRewarms < MAX_CART_SENSOR_REWARMS) {
				cartSensorRewarms++;
				log.info("Cart add 403 for {} (size {}); re-warming Akamai sensor ({}/{}) and retrying", productUrl,
						size, cartSensorRewarms, MAX_CART_SENSOR_REWARMS);
				warmCartSensor();
				status = postCartItem(request, cartAddUrl, payload, productUrl, size);
			}
			if (status < 200 || status >= 300) {
				if (status != 403) {
					return CartAddResult.failed(status, "basket API rejected the add");
				}
				log.info("Direct cart add still 403 for {} (size {}); trying warmed in-page add", productUrl, size);
				int fallbackStatus = addToCartViaPageFetch(productUrl, cartAddUrl, payload);
				if (fallbackStatus < 200 || fallbackStatus >= 300) {
					return CartAddResult.blocked(fallbackStatus > 0 ? fallbackStatus : status,
							"bot protection refused the basket call");
				}
			}

			boolean inCart = fetchCartItems(request).stream().anyMatch(item -> configSku.equals(item.configSku()));
			log.atInfo()
				.addArgument(productUrl)
				.addArgument(size)
				.addArgument(inCart)
				.log("addToCart result for {} (size {}): inCart={}");
			return inCart ? CartAddResult.added() : CartAddResult.failed(status, "basket did not confirm the item");
		}
		catch (Exception e) {
			log.error("addToCart failed for {}: {}", productUrl, e.getMessage(), e);
			return CartAddResult.failed(0, e.getMessage());
		}
	}

	/**
	 * Replays the basket POST from inside a warmed article page. Returns the HTTP status
	 * of that fetch, or {@code -1} when the page could not run it at all.
	 */
	private int addToCartViaPageFetch(String productUrl, String cartAddUrl, java.util.Map<String, Object> payload) {
		try (var page = openPage()) {
			page.navigate(productUrl, new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
			page.waitForLoadState(LoadState.DOMCONTENTLOADED);
			acceptCookieBannerOnce(page);
			// The stockcart endpoint is Akamai-guarded; let the sensor observe genuine
			// interaction on the article page so _abck is solved before the in-page POST.
			authService.warmUpBotSensor(page);

			@SuppressWarnings("unchecked")
			var result = (java.util.Map<String, Object>) page.evaluate("""
					async ({ url, body, referer, origin }) => {
					  const response = await fetch(url, {
					    method: 'POST',
					    credentials: 'include',
					    headers: {
					      'Content-Type': 'application/json',
					      'Accept': 'application/json',
					      'X-Requested-With': 'XMLHttpRequest',
					      'Referer': referer,
					      'Origin': origin
					    },
					    body: JSON.stringify(body)
					  });
					  let text = '';
					  try {
					    text = await response.text();
					  } catch (_) {
					    text = '';
					  }
					  return {
					    ok: response.ok,
					    status: response.status,
					    body: text
					  };
					}
					""", java.util.Map.of("url", cartAddUrl, "body", payload, "referer", productUrl, "origin",
					origin(productUrl)));

			boolean ok = Boolean.TRUE.equals(result.get("ok"));
			Number status = result.get("status") instanceof Number n ? n : Integer.valueOf(-1);
			String body = safeSnippet(String.valueOf(result.getOrDefault("body", "")));
			if (!ok) {
				log.warn("In-page cart add fetch returned status {} for {} (body={})", status.intValue(), productUrl,
						body);
			}
			return status.intValue();
		}
		catch (Exception e) {
			log.warn("In-page cart add fallback failed for {}: {}", productUrl, e.getMessage());
			return -1;
		}
	}

	/**
	 * Issues the stockcart basket POST once and returns the HTTP status (or {@code -1} on
	 * a transport error). Non-2xx responses are logged with a body snippet so an Akamai
	 * {@code edge_error:halt} rejection is visible in the run log.
	 */
	private int postCartItem(APIRequestContext request, String cartAddUrl, java.util.Map<String, Object> payload,
			String productUrl, String size) {
		APIResponse response = null;
		try {
			response = request.post(cartAddUrl,
					com.microsoft.playwright.options.RequestOptions.create()
						.setHeader("Content-Type", "application/json")
						.setHeader("Accept", "application/json")
						.setHeader("X-Requested-With", "XMLHttpRequest")
						.setHeader("Origin", origin(productUrl))
						.setHeader("Referer", productUrl)
						.setData(objectMapper.writeValueAsString(payload)));
			int status = response.status();
			if (status < 200 || status >= 300) {
				log.warn("Cart add API returned status {} for {} (size {}), endpoint={}, body={}", status, productUrl,
						size, cartAddUrl, safeSnippet(response.text()));
			}
			return status;
		}
		catch (Exception e) {
			log.warn("Cart add POST failed for {} (size {}): {}", productUrl, size, e.getMessage());
			return -1;
		}
		finally {
			if (response != null) {
				response.dispose();
			}
		}
	}

	/**
	 * Warms the Akamai stockcart sensor at most once per run, before the cart-add burst.
	 */
	private synchronized void ensureCartSensorWarm() {
		if (!cartSensorWarmed) {
			warmCartSensor();
		}
	}

	/**
	 * Loads a lounge page and drives an interactive Akamai BotManager warm-up so the
	 * shared context {@code _abck} cookie is promoted to a solved state. The stockcart
	 * POST reuses that cookie, so a single warm-up unblocks the whole add burst; it is
	 * re-run on demand when a 403 shows the token has decayed. Best-effort — a warm-up
	 * failure is logged and the add proceeds regardless.
	 */
	private synchronized void warmCartSensor() {
		try (var page = openPage()) {
			page.navigate(properties.zalando().baseUrl(),
					new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
			page.waitForLoadState(LoadState.DOMCONTENTLOADED);
			acceptCookieBannerOnce(page);
			authService.warmUpBotSensor(page);
			cartSensorWarmed = true;
			log.info("Akamai stockcart sensor warm-up complete");
		}
		catch (Exception e) {
			log.warn("Akamai stockcart sensor warm-up failed (continuing anyway): {}", e.getMessage());
		}
	}

	private String safeSnippet(String body) {
		if (body == null) {
			return "";
		}
		String trimmed = body.strip();
		return trimmed.length() <= 400 ? trimmed : trimmed.substring(0, 400);
	}

	/**
	 * Resolves the per-size stockcart sku ({@code simpleSku}) for the requested size from
	 * an article's {@code simples}, matching on the display size ({@code filterValue}) or
	 * the EU country size and requiring the size to be in stock. Returns {@code null}
	 * when the size is not available.
	 */
	private static String simpleSkuForSize(JsonNode simples, String size) {
		if (simples == null || !simples.isArray() || size == null || size.isBlank()) {
			return null;
		}
		String target = size.trim();
		for (JsonNode simple : simples) {
			if (!"AVAILABLE".equals(simple.path("stockStatus").asString(""))) {
				continue;
			}
			String filterValue = simple.path("filterValue").asString("").trim();
			String euSize = simple.path("country_sizes").path("eu").asString("").trim();
			if (target.equalsIgnoreCase(filterValue) || target.equalsIgnoreCase(euSize)) {
				String simpleSku = simple.path("sku").asString("").trim();
				if (!simpleSku.isBlank()) {
					return simpleSku;
				}
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

	@Override
	public CartAddResult refreshCartItem(String productUrl, String size) {
		try {
			// Removing and re-adding the article mutates the basket, which resets
			// Zalando's server-side reservation timer — a plain presence check does not.
			// This is what genuinely prolongs the cart hold during keep-alive.
			removeFromCart(productUrl);
			var readded = addToCart(productUrl, size);
			if (!readded.isAdded()) {
				log.warn("Keep-alive refresh could not re-add {} (size {}): {}", productUrl, size, readded.describe());
			}
			return readded;
		}
		catch (Exception e) {
			log.error("refreshCartItem failed for {}: {}", productUrl, e.getMessage(), e);
			return CartAddResult.failed(0, e.getMessage());
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
		return newLivePage();
	}

	/**
	 * Opens a page, healing a silently-dropped Patchright connection.
	 * {@code browser.isConnected()} can still report {@code true} after the sidecar
	 * dropped the remote browser/context; {@code context.newPage()} then fails deep in
	 * the driver with an opaque {@link java.util.NoSuchElementException} (no message),
	 * which used to bubble up as {@code [UNKNOWN]: null}. Force a full reconnect and
	 * retry once, then surface a descriptive {@link IllegalStateException} naming the
	 * endpoint and connection state instead of the bare driver error.
	 */
	private Page newLivePage() {
		try {
			return context.newPage();
		}
		catch (Exception e) {
			log.warn("Could not open a page on the Patchright context ({}: {}); forcing a full reconnect to {}",
					e.getClass().getSimpleName(), e.getMessage(), properties.zalando().browserWsEndpoint());
			forceReconnect();
			try {
				return context.newPage();
			}
			catch (Exception retry) {
				throw new IllegalStateException(
						("Patchright browser at %s produced no usable page even after reconnect (connected=%s); the "
								+ "browser server likely never accepted the connection or dropped the remote "
								+ "browser/context. Original error %s: %s")
							.formatted(properties.zalando().browserWsEndpoint(),
									browser != null && browser.isConnected(), retry.getClass().getName(),
									retry.getMessage()),
						retry);
			}
		}
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

	/**
	 * Tears down the {@code Browser} and {@code context} and rebuilds both from scratch.
	 * Used when {@code isConnected()} lied — the Patchright sidecar silently dropped the
	 * remote browser, so the cached client objects are stale even though the websocket
	 * still appears up and {@code resetContext()} (which only recreates the context)
	 * would hand back another dead context.
	 */
	private void forceReconnect() {
		if (context != null) {
			try {
				context.close();
			}
			catch (Exception e) {
				log.debug("Could not close stale browser context during forced reconnect: {}", e.getMessage());
			}
			context = null;
		}
		if (browser != null) {
			try {
				browser.close();
			}
			catch (Exception e) {
				log.debug("Could not close stale browser during forced reconnect: {}", e.getMessage());
			}
			browser = null;
		}
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
		cartSensorWarmed = false;
		cartSensorRewarms = 0;
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
