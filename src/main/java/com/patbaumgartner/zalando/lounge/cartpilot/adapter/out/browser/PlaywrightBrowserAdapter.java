package com.patbaumgartner.zalando.lounge.cartpilot.adapter.out.browser;

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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Playwright-backed implementation of {@link BrowserPort}.
 *
 * Uses a single {@link BrowserContext} (with its own cookie jar) throughout the
 * application lifetime; each operation opens a fresh {@link Page} and closes it when
 * done.
 *
 * <p>
 * Every Zalando API call is issued from inside a page through {@link InPageHttpClient}.
 * Playwright's own {@code APIRequestContext} shares the cookie jar but runs on Node's
 * HTTP stack, and Akamai BotManager answers those requests with {@code 403} — which used
 * to abort a cart add before the article page was ever opened.
 */
@Component
@Profile("!test")
public class PlaywrightBrowserAdapter implements BrowserPort {

	private static final Logger log = LoggerFactory.getLogger(PlaywrightBrowserAdapter.class);

	private static final String SIZE_OPTION_SELECTOR = "input[name='size'][data-testid='article-size-toggle']";

	/** Path fragment of the basket write the article page's own button issues. */
	private static final String STOCKCART_PATH = "/api/phoenix/stockcart/cart";

	/** Sentinel for "the page never issued a basket write". */
	private static final int NO_WRITE_OBSERVED = -1;

	/**
	 * Accessible name of the article page's add-to-cart button (DE/EN). Deliberately
	 * phrase-based: a bare "Warenkorb" also matches the header's basket button.
	 */
	private static final java.util.regex.Pattern ADD_TO_CART_NAME = java.util.regex.Pattern.compile(
			"in den warenkorb|zum warenkorb hinzuf|in den einkaufswagen|add to (cart|basket)|zur.?ck.legen",
			java.util.regex.Pattern.CASE_INSENSITIVE);

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
	 * How many times the cart API is polled to confirm the button-click add landed in the
	 * basket. The click issues the basket request asynchronously, so the write can lag
	 * the first read.
	 */
	private static final int CART_CONFIRM_ATTEMPTS = 5;

	private static final long CART_CONFIRM_INTERVAL_MS = 600;

	private final Playwright playwright;

	private final AuthenticationService authService;

	private final CampaignScraper campaignScraper;

	private final CartPilotProperties properties;

	private final ObjectMapper objectMapper;

	private final InPageHttpClient http;

	private final CartApi cartApi;

	private Browser browser;

	private BrowserContext context;

	/**
	 * Long-lived page parked on the lounge origin, used to issue the API calls that are
	 * not tied to an article page (cart reads, removals, article lookups during a scan).
	 * Reusing one page keeps hundreds of scan requests off the page-open/navigate
	 * treadmill and lets Akamai see one coherent, sensor-warmed document.
	 */
	private Page apiPage;

	private boolean cookieConsentHandled;

	private long lastApiRequestAtNanos;

	public PlaywrightBrowserAdapter(Playwright playwright, Browser browser, AuthenticationService authService,
			CampaignScraper campaignScraper, CartPilotProperties properties, ObjectMapper objectMapper) {
		this.playwright = playwright;
		this.browser = browser;
		this.authService = authService;
		this.campaignScraper = campaignScraper;
		this.properties = properties;
		this.objectMapper = objectMapper;
		this.http = new InPageHttpClient(properties.zalando().elementTimeoutMs());
		this.cartApi = new CartApi(this.http, objectMapper, properties.zalando().cartApiUrl());
	}

	@Override
	public synchronized void ensureAuthenticated() {
		verifyBrowserAvailable();

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
		return campaignScraper.scrapeOpenCampaigns(apiPage(), properties.zalando().campaignUrl());
	}

	/**
	 * Scrapes a campaign's articles from the shared API page. The page must already sit
	 * on the lounge origin: an in-page {@code fetch()} from a blank page has an opaque
	 * origin, which the catalog API rejects as cross-origin.
	 */
	@Override
	public List<DiscoveredProduct> scrapeProducts(Campaign campaign) {
		return campaignScraper.scrapeProducts(apiPage(), campaign);
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
		JsonNode article = fetchArticleViaApiPage(productUrl).article();
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
	 * ({@code /api/phoenix/catalog/events/{campaignId}/articles/{sku}}) with a
	 * browser-native request issued from {@code page}, retrying on 429. The returned
	 * article is {@code null} when the id could not be derived or the request failed.
	 */
	private ArticleFetch fetchArticle(Page page, String productUrl) {
		String campaignId = campaignId(productUrl);
		String sku = articleId(productUrl);
		if (campaignId == null || sku == null) {
			return ArticleFetch.failed(0);
		}
		String apiUrl = origin(productUrl) + "/api/phoenix/catalog/events/" + campaignId + "/articles/" + sku;
		for (int attempt = 0; attempt <= SIZE_API_RATE_LIMIT_RETRIES; attempt++) {
			paceApiRequest();
			var response = http.get(page, apiUrl);

			if (response.isRateLimited()) {
				if (attempt == SIZE_API_RATE_LIMIT_RETRIES) {
					log.warn("Article request still rate-limited (429) after {} retries for {}",
							SIZE_API_RATE_LIMIT_RETRIES, apiUrl);
					return ArticleFetch.failed(429);
				}
				long backoff = SIZE_API_RETRY_BACKOFF_MS * (1L << attempt);
				log.debug("Rate-limited (429) on {}; retrying in {} ms (attempt {}/{})", apiUrl, backoff, attempt + 1,
						SIZE_API_RATE_LIMIT_RETRIES);
				if (!sleepQuietly(backoff)) {
					return ArticleFetch.failed(429);
				}
				continue;
			}

			if (!response.ok()) {
				log.warn("Article request returned {} for {} ({})", response.describe(), apiUrl,
						response.bodySnippet());
				return ArticleFetch.failed(response.status());
			}
			try {
				JsonNode article = objectMapper.readTree(response.body());
				return article.isObject() ? new ArticleFetch(article, response.status())
						: ArticleFetch.failed(response.status());
			}
			catch (Exception e) {
				log.debug("Could not parse article detail for {}: {}", apiUrl, e.getMessage());
				return ArticleFetch.failed(response.status());
			}
		}
		return ArticleFetch.failed(0);
	}

	/**
	 * Article lookup over the shared API page, recreating that page once when it turns
	 * out to be dead (a status of {@code 0} means no HTTP response was produced at all).
	 */
	private ArticleFetch fetchArticleViaApiPage(String productUrl) {
		var fetched = fetchArticle(apiPage(), productUrl);
		if (fetched.article() == null && fetched.status() == 0) {
			closeApiPage();
			fetched = fetchArticle(apiPage(), productUrl);
		}
		return fetched;
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
		String campaignId = campaignId(productUrl);
		String configSku = articleId(productUrl);
		if (campaignId == null || configSku == null) {
			log.warn("Could not derive campaign/article id for cart add: {}", productUrl);
			return CartAddResult.failed(0, "unrecognised product URL");
		}
		try {
			return reserveOnArticlePage(productUrl, size, campaignId, configSku);
		}
		catch (Exception e) {
			log.error("addToCart failed for {}: {}", productUrl, e.getMessage(), e);
			return CartAddResult.failed(0, e.getMessage());
		}
	}

	/**
	 * Reserves the article by driving its detail page like a human: warm the Akamai
	 * sensor, select the requested size, then click the page's own add-to-cart button so
	 * the basket request is issued browser-natively by the SPA.
	 *
	 * <p>
	 * Nothing is looked up in the catalog API before the page is driven. That pre-check
	 * ran over Playwright's Node HTTP stack and its {@code 403} aborted the whole add
	 * without the article ever being opened — and the rendered page is the better source
	 * anyway, because a size that cannot be picked there is genuinely gone.
	 */
	private CartAddResult reserveOnArticlePage(String productUrl, String size, String campaignId, String configSku) {
		try (var page = openPage()) {
			page.navigate(productUrl, new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
			page.waitForLoadState(LoadState.DOMCONTENTLOADED);
			acceptCookieBannerOnce(page);
			authService.warmUpBotSensor(page);

			var before = cartApi.read(page);

			var selection = selectSize(page, productUrl, size);
			if (selection == SizeSelection.NO_OPTIONS) {
				return CartAddResult.failed(0, "no size options rendered on the article page");
			}
			if (selection == SizeSelection.NOT_OFFERED) {
				return CartAddResult.sizeUnavailable("size " + size + " is not selectable on the article page");
			}

			var basketWrite = recordBasketWrites(page);
			var addButton = findAddToCartButton(page);
			if (addButton != null) {
				authService.clickHumanLike(page, addButton);
			}
			else {
				log.warn("No add-to-cart button found on {} — falling back to the in-page basket call", productUrl);
			}

			var result = confirmOrRetryInPage(page, productUrl, size, campaignId, configSku, before, basketWrite,
					addButton != null);
			log.atInfo()
				.addArgument(productUrl)
				.addArgument(size)
				.addArgument(result::describe)
				.log("addToCart result for {} (size {}): {}");
			return result;
		}
		catch (Exception e) {
			log.warn("Cart add on the article page failed for {} (size {}): {}", productUrl, size, e.getMessage());
			return CartAddResult.failed(0, e.getMessage());
		}
	}

	/**
	 * Turns what the page did into a verdict. The basket itself is the authority; the
	 * status of the write the page issued matters only when the basket cannot be read
	 * back, and it also decides whether retrying is safe — replaying a write that already
	 * succeeded would reserve the article twice.
	 */
	private CartAddResult confirmOrRetryInPage(Page page, String productUrl, String size, String campaignId,
			String configSku, CartApi.CartSnapshot before, AtomicInteger basketWrite, boolean clicked) {
		var confirmation = confirmInCart(page, configSku, before);
		if (confirmation == CartConfirmation.CONFIRMED) {
			return CartAddResult.added();
		}

		int writeStatus = basketWrite.get();
		if (writeStatus >= 200 && writeStatus < 300) {
			if (confirmation == CartConfirmation.UNREADABLE) {
				log.info(
						"Basket write for {} answered HTTP {} but the cart could not be read back — trusting the write",
						productUrl, writeStatus);
				return CartAddResult.added();
			}
			return CartAddResult.failed(writeStatus,
					"the page's basket call answered HTTP " + writeStatus + " but the cart does not hold the item");
		}
		if (writeStatus == 403 || writeStatus == 429) {
			return CartAddResult.blocked(writeStatus, "bot protection refused the basket call the page issued");
		}
		if (writeStatus != NO_WRITE_OBSERVED) {
			return CartAddResult.failed(writeStatus, "the page's basket call answered HTTP " + writeStatus);
		}

		return addViaPageFetch(page, productUrl, size, campaignId, configSku, before, clicked);
	}

	/**
	 * Last resort when the page issued no basket write at all — a missing button, or a
	 * click that went nowhere. The call is made from the article page itself, so it rides
	 * the same Chromium network stack and the same freshly warmed sensor as the button
	 * would have: this is not the Node-side POST Akamai used to refuse.
	 */
	private CartAddResult addViaPageFetch(Page page, String productUrl, String size, String campaignId,
			String configSku, CartApi.CartSnapshot before, boolean clicked) {
		String origin = clicked ? "the add-to-cart click did not reach the basket"
				: "no add-to-cart button on the page";

		var articleFetch = fetchArticle(page, productUrl);
		if (articleFetch.article() == null) {
			String detail = origin + " and the article detail answered HTTP " + articleFetch.status();
			return articleFetch.isBotWall() ? CartAddResult.blocked(403, detail)
					: CartAddResult.failed(articleFetch.status(), detail);
		}
		String simpleSku = simpleSkuForSize(articleFetch.article().path("simples"), size);
		if (simpleSku == null) {
			return CartAddResult.sizeUnavailable("size " + size + " not purchasable");
		}

		log.info("Retrying the basket call in-page for {} (size {}): {}", productUrl, size, origin);
		var response = cartApi.addItem(page, campaignId, configSku, simpleSku);
		if (!response.ok()) {
			log.warn("In-page basket call for {} (size {}) answered {} ({})", productUrl, size, response.describe(),
					response.bodySnippet());
			return response.isBotWall() || response.isRateLimited()
					? CartAddResult.blocked(response.status(), "bot protection refused the basket call")
					: CartAddResult.failed(response.status(), "basket call answered " + response.describe());
		}

		var confirmation = confirmInCart(page, configSku, before);
		if (confirmation == CartConfirmation.ABSENT) {
			return CartAddResult.failed(response.status(), "basket accepted the item but the cart does not hold it");
		}
		return CartAddResult.added();
	}

	/**
	 * Watches the page for the basket write its own add-to-cart button issues, so a
	 * refusal is reported with the status Zalando actually returned rather than a guess,
	 * and a write that already succeeded is never replayed.
	 */
	private AtomicInteger recordBasketWrites(Page page) {
		var lastStatus = new AtomicInteger(NO_WRITE_OBSERVED);
		page.onResponse(response -> {
			if (response.url().contains(STOCKCART_PATH) && "POST".equalsIgnoreCase(response.request().method())) {
				lastStatus.set(response.status());
			}
		});
		return lastStatus;
	}

	private enum SizeSelection {

		SELECTED, NOT_OFFERED, NO_OPTIONS

	}

	/**
	 * Clicks the size radio's visible label for the requested size on the article page.
	 * The rendered page is authoritative: a size with no selectable label is gone, which
	 * the caller reports as sold out without a separate catalog lookup.
	 */
	private SizeSelection selectSize(Page page, String productUrl, String size) {
		var sizeInputs = page.locator(SIZE_OPTION_SELECTOR);
		try {
			sizeInputs.first()
				.waitFor(new Locator.WaitForOptions().setTimeout(properties.zalando().elementTimeoutMs()));
		}
		catch (Exception e) {
			log.warn("No size options rendered on {} — cannot select size {}", productUrl, size);
			return SizeSelection.NO_OPTIONS;
		}
		String target = size == null ? "" : size.trim();
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
			if (target.equalsIgnoreCase(optionSize)) {
				authService.clickHumanLike(page, label.first());
				return SizeSelection.SELECTED;
			}
		}
		log.warn("Size '{}' has no selectable label on {}", size, productUrl);
		return SizeSelection.NOT_OFFERED;
	}

	/**
	 * Locates the article page's add-to-cart control. The live page carries no
	 * {@code data-testid} on any cart button, so the accessible name decides — and it has
	 * to match the whole phrase ("In den Warenkorb"), never the bare word "Warenkorb":
	 * that also names the basket button in the header, which sits earlier in the DOM and
	 * merely navigates away instead of reserving anything.
	 */
	private Locator findAddToCartButton(Page page) {
		var byTestId = firstUsable(page.locator("button[data-testid*='add-to-cart' i], [data-testid*='add-to-cart' i] "
				+ "button, button[data-testid*='addToCart' i]"));
		if (byTestId != null) {
			return byTestId;
		}
		return firstUsable(page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
				new Page.GetByRoleOptions().setName(ADD_TO_CART_NAME)));
	}

	/**
	 * Returns the first candidate a person could actually click. Article pages carry
	 * hidden duplicates of the basket button (mobile/sticky variants), and clicking one
	 * of those silently does nothing; anything living in the page chrome is skipped
	 * outright.
	 */
	private Locator firstUsable(Locator candidates) {
		int count = candidates.count();
		for (int i = 0; i < count; i++) {
			var candidate = candidates.nth(i);
			try {
				if (!candidate.isVisible() || !candidate.isEnabled()) {
					continue;
				}
				boolean inPageChrome = Boolean.TRUE
					.equals(candidate.evaluate("element => !!element.closest('header, nav, [role=banner]')"));
				if (!inPageChrome) {
					return candidate;
				}
			}
			catch (Exception e) {
				log.debug("Skipping add-to-cart candidate {}: {}", i, e.getMessage());
			}
		}
		return null;
	}

	private enum CartConfirmation {

		CONFIRMED, ABSENT, UNREADABLE

	}

	/**
	 * Polls the basket until the article shows up as a line the pre-click snapshot did
	 * not have, a short budget elapses, or it becomes clear the cart cannot be read at
	 * all. The click issues the basket request asynchronously, so an immediate read can
	 * race ahead of the write.
	 */
	private CartConfirmation confirmInCart(Page page, String configSku, CartApi.CartSnapshot before) {
		boolean everReadable = false;
		for (int attempt = 0; attempt < CART_CONFIRM_ATTEMPTS; attempt++) {
			var current = cartApi.read(page);
			everReadable |= current.readable();
			if (current.gainedLineFor(configSku, before)) {
				return CartConfirmation.CONFIRMED;
			}
			if (!sleepQuietly(CART_CONFIRM_INTERVAL_MS)) {
				break;
			}
		}
		return everReadable ? CartConfirmation.ABSENT : CartConfirmation.UNREADABLE;
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

			var snapshot = readCart();
			if (!snapshot.readable()) {
				log.warn("Cart could not be read (HTTP {}) — leaving {} untouched", snapshot.status(), productUrl);
				return;
			}

			var page = apiPage();
			boolean removed = false;
			for (var cartItem : snapshot.items()) {
				if (articleId.equals(cartItem.configSku())) {
					removed |= cartApi.removeItem(page, cartItem);
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
		try {
			String articleId = articleId(productUrl);
			if (articleId == null || articleId.isBlank()) {
				return false;
			}
			return readCart().contains(articleId);
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
	 * Reads the basket over the shared API page, recreating that page once when the read
	 * produced no HTTP response at all (a dead page after a dropped Patchright
	 * connection).
	 */
	private CartApi.CartSnapshot readCart() {
		var snapshot = cartApi.read(apiPage());
		if (!snapshot.readable() && snapshot.status() == 0) {
			closeApiPage();
			snapshot = cartApi.read(apiPage());
		}
		return snapshot;
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
			var snapshot = readCart();
			if (!snapshot.readable()) {
				log.warn("Cart could not be read (HTTP {}) — nothing was cleared", snapshot.status());
				return 0;
			}

			var page = apiPage();
			int removed = 0;
			for (var cartItem : snapshot.items()) {
				if (cartApi.removeItem(page, cartItem)) {
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
	 * Returns the shared page the non-article API calls are issued from, parked on the
	 * lounge origin so every {@code fetch()} is same-origin and carries the session
	 * cookies. Reusing one page lets a scan issue hundreds of authenticated requests
	 * without opening (and tearing down) a {@link Page} per article, and keeps a single
	 * document — with its Akamai sensor script — alive for the whole run.
	 */
	private synchronized Page apiPage() {
		ensureConnected();
		if (apiPage != null && !apiPage.isClosed()) {
			return apiPage;
		}
		var page = newLivePage();
		page.navigate(properties.zalando().baseUrl(),
				new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
		page.waitForLoadState(LoadState.DOMCONTENTLOADED);
		acceptCookieBannerOnce(page);
		apiPage = page;
		return apiPage;
	}

	private synchronized void closeApiPage() {
		if (apiPage != null) {
			try {
				apiPage.close();
			}
			catch (Exception e) {
				log.debug("Could not close the shared API page: {}", e.getMessage());
			}
			apiPage = null;
		}
	}

	/** Sleeps, returning {@code false} when the thread was interrupted meanwhile. */
	private static boolean sleepQuietly(long millis) {
		try {
			Thread.sleep(millis);
			return true;
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
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
			apiPage = null;
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
		closeApiPage();
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
		apiPage = null;
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
