package com.patbaumgartner.zalando.lounge.cartpilot.adapter.out.browser;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitUntilState;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.Campaign;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.Category;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.DiscoveredProduct;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.ProductStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Fetches open campaigns and their articles from the Zalando Lounge phoenix JSON APIs.
 *
 * <p>
 * The Lounge web app is a single-page Angular application that no longer exposes a
 * {@code window.__INITIAL_STATE__} blob, so campaigns and products are read straight from
 * the authenticated JSON endpoints instead of being scraped from server-rendered markup:
 * <ul>
 * <li>{@code GET /api/phoenix/mylounge/campaigns} → the currently open campaigns.</li>
 * <li>{@code GET /api/phoenix/catalog/events/{campaignId}/articles} → a campaign's
 * articles, each already carrying brand, gender, prices and per-size stock.</li>
 * </ul>
 * Both are queried with a browser-native {@code fetch()} issued from the page (see
 * {@link InPageHttpClient}), which shares the authenticated context's cookies and
 * Chromium's own network stack, so no page rendering or DOM scraping is required.
 */
@Component
@Profile("!test")
class CampaignScraper {

	private static final Logger log = LoggerFactory.getLogger(CampaignScraper.class);

	private static final ZoneId ZURICH = ZoneId.of("Europe/Zurich");

	private static final String CAMPAIGNS_API_PATH = "/api/phoenix/mylounge/campaigns";

	private static final String ARTICLES_API_PATH = "/api/phoenix/catalog/events/%s/articles";

	/** Zalando's catalog page size; also the maximum returned per request. */
	private static final int ARTICLE_PAGE_SIZE = 84;

	/** Hard cap on article pages fetched per campaign (84 × 6 = 504 articles). */
	private static final int MAX_ARTICLE_PAGES = 6;

	/** Retries when the catalog article API answers 429 (Akamai rate limiting). */
	private static final int ARTICLE_RATE_LIMIT_RETRIES = 5;

	/** Base backoff for 429 retries, doubled each attempt. */
	private static final long ARTICLE_RETRY_BACKOFF_MS = 700;

	/**
	 * Minimum spacing between catalog article requests. Scanning every open campaign
	 * fires hundreds of listing requests; without pacing the burst trips Akamai's rate
	 * limiter (429) and whole campaigns come back empty. Throttling keeps the scan under
	 * the threshold so it completes without losing products.
	 */
	private static final long ARTICLE_MIN_INTERVAL_MS = 300;

	/** de-CH language id keying the localized product URL in {@code urlPath}. */
	private static final String URL_PATH_LANG = "40";

	private static final String DEFAULT_ORIGIN = "https://www.zalando-lounge.ch";

	/** Budget for a single catalog API call made from inside the page. */
	private static final long API_TIMEOUT_MS = 30_000;

	private final ObjectMapper objectMapper;

	private final AuthenticationService authenticationService;

	private final InPageHttpClient http;

	private long lastArticleRequestAtNanos;

	CampaignScraper(ObjectMapper objectMapper, AuthenticationService authenticationService) {
		this.objectMapper = objectMapper;
		this.authenticationService = authenticationService;
		this.http = new InPageHttpClient(API_TIMEOUT_MS);
	}

	/**
	 * Returns the currently open campaigns from the authenticated My Lounge API. Ended or
	 * not-yet-started campaigns are filtered out; every remaining campaign is shoppable
	 * right now, regardless of which day it launched (Lounge campaigns run for several
	 * days).
	 */
	List<Campaign> scrapeOpenCampaigns(Page page, String campaignUrl) {
		// Land on /event first: this establishes the site origin/cookies and lets the
		// Patchright browser clear Akamai before we hit the JSON API.
		page.navigate(campaignUrl, new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
		page.waitForLoadState(LoadState.DOMCONTENTLOADED);
		authenticationService.acceptCookieBannerIfPresent(page);

		String apiUrl = origin(campaignUrl) + CAMPAIGNS_API_PATH;
		var response = http.get(page, apiUrl);
		if (!response.ok()) {
			log.error("My Lounge campaigns API returned {} ({}) — {}", response.describe(), apiUrl,
					response.bodySnippet());
			return List.of();
		}
		try {
			return parseOpenCampaigns(response.body());
		}
		catch (Exception e) {
			log.error("Failed to fetch open campaigns from {}: {}", apiUrl, e.getMessage(), e);
			return List.of();
		}
	}

	/**
	 * Fetches every article for a campaign from the catalog API. Each article already
	 * carries brand, gender, prices and per-size stock, so no per-product detail lookup
	 * or page render is required here.
	 */
	List<DiscoveredProduct> scrapeProducts(Page page, Campaign campaign) {
		String origin = origin(campaign.campaignUrl().isBlank() ? DEFAULT_ORIGIN : campaign.campaignUrl());
		var products = new ArrayList<DiscoveredProduct>();

		for (int pageNo = 0; pageNo < MAX_ARTICLE_PAGES; pageNo++) {
			var articles = fetchArticlePage(page, origin, campaign.campaignId(), pageNo);
			if (articles.isEmpty()) {
				break;
			}
			for (JsonNode article : articles) {
				try {
					products.add(toDiscoveredProduct(article, campaign, origin));
				}
				catch (Exception e) {
					log.debug("Skipped unparseable article in campaign {}: {}", campaign.campaignId(), e.getMessage());
				}
			}
			if (articles.size() < ARTICLE_PAGE_SIZE) {
				break;
			}
		}

		log.atInfo()
			.addArgument(products.size())
			.addArgument(campaign.campaignId())
			.log("Scraped {} products from campaign {}");
		return products;
	}

	// ── Private helpers ────────────────────────────────────────

	private List<Campaign> parseOpenCampaigns(String body) {
		if (body == null || body.isBlank()) {
			return List.of();
		}
		JsonNode open;
		try {
			open = objectMapper.readTree(body).path("open_campaigns");
		}
		catch (Exception e) {
			log.error("Failed to parse My Lounge campaigns response: {}", e.getMessage(), e);
			return List.of();
		}
		if (!open.isArray()) {
			log.warn("My Lounge campaigns response contained no open_campaigns array");
			return List.of();
		}

		var now = Instant.now();
		var campaigns = new ArrayList<Campaign>();
		for (JsonNode node : open) {
			String id = node.path("campaign_id").asString("").trim();
			if (id.isBlank()) {
				continue;
			}
			var endsAt = parseInstant(node.path("ends_at").asString(""));
			if (endsAt != null && endsAt.isBefore(now)) {
				continue; // already closed
			}
			var startsAt = parseInstant(node.path("starts_at").asString(""));
			if (startsAt != null && startsAt.isAfter(now)) {
				continue; // not open yet
			}
			String title = node.path("name").asString(id);
			String url = node.path("url").asString("").trim();
			LocalDate startDate = startsAt != null ? LocalDate.ofInstant(startsAt, ZURICH) : LocalDate.now(ZURICH);
			campaigns.add(new Campaign(id, title, startDate, url));
		}
		log.info("Fetched {} open campaign(s) from My Lounge API", campaigns.size());
		return campaigns;
	}

	private List<JsonNode> fetchArticlePage(Page page, String origin, String campaignId, int pageNo) {
		String apiUrl = origin + ARTICLES_API_PATH.formatted(campaignId) + "?size=" + ARTICLE_PAGE_SIZE
				+ "&fields=1&sort=relevance&no_soldout=0&page=" + pageNo;
		for (int attempt = 0; attempt <= ARTICLE_RATE_LIMIT_RETRIES; attempt++) {
			paceArticleRequest();
			var response = http.get(page, apiUrl);
			if (response.isRateLimited() && attempt < ARTICLE_RATE_LIMIT_RETRIES) {
				sleep(ARTICLE_RETRY_BACKOFF_MS * (1L << attempt));
				continue;
			}
			if (!response.ok()) {
				// A campaign can close between listing and scrape (404/410); only the
				// first page is worth logging so a genuinely empty result stays
				// quiet.
				if (pageNo == 0) {
					log.warn("Articles API returned {} for campaign {} ({})", response.describe(), campaignId,
							response.bodySnippet());
				}
				return List.of();
			}
			try {
				var root = objectMapper.readTree(response.body());
				if (!root.isArray()) {
					return List.of();
				}
				var list = new ArrayList<JsonNode>();
				root.forEach(list::add);
				return list;
			}
			catch (Exception e) {
				log.debug("Failed to fetch articles page {} for campaign {}: {}", pageNo, campaignId, e.getMessage());
				return List.of();
			}
		}
		return List.of();
	}

	private static void sleep(long millis) {
		try {
			Thread.sleep(millis);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	/**
	 * Throttles catalog article requests to at most one per
	 * {@link #ARTICLE_MIN_INTERVAL_MS}. Only ever reached through
	 * {@code PlaywrightBrowserAdapter}'s {@code synchronized} entry points, so
	 * {@code lastArticleRequestAtNanos} needs no further guarding.
	 */
	private void paceArticleRequest() {
		long now = System.nanoTime();
		if (lastArticleRequestAtNanos != 0) {
			long elapsedMs = (now - lastArticleRequestAtNanos) / 1_000_000L;
			long waitMs = ARTICLE_MIN_INTERVAL_MS - elapsedMs;
			if (waitMs > 0) {
				sleep(waitMs);
			}
		}
		lastArticleRequestAtNanos = System.nanoTime();
	}

	private DiscoveredProduct toDiscoveredProduct(JsonNode article, Campaign campaign, String origin) {
		String brand = article.path("brand").asString("").trim();
		String name = firstNonBlank(article.path("nameCategoryTag").asString("").trim(),
				article.path("nameShop").asString("").trim(), "Unknown");
		var gender = CatalogArticleSupport.resolveGender(article.path("gender"));
		var sizes = CatalogArticleSupport.availableSizes(article.path("simples"));
		var originalPrice = CatalogArticleSupport.centsToAmount(article.path("price"));
		var loungePrice = CatalogArticleSupport.centsToAmount(article.path("specialPrice"));
		int discountPct = article.path("savings").isNumber() ? article.path("savings").asInt()
				: computeDiscount(originalPrice, loungePrice);
		String productUrl = resolveProductUrl(article, origin, campaign);

		return new DiscoveredProduct(null, campaign.campaignId(), brand.isBlank() ? "Unknown" : brand, name,
				inferCategory(name), gender, sizes, originalPrice, loungePrice, discountPct, productUrl,
				ProductStatus.DISCOVERED, LocalDateTime.now());
	}

	/**
	 * Builds the absolute product URL from the article's localized {@code urlPath} (the
	 * de-CH entry, falling back to any available locale), resolved against the site
	 * origin. Falls back to the campaign URL when no article path is present.
	 */
	private String resolveProductUrl(JsonNode article, String origin, Campaign campaign) {
		JsonNode urlPath = article.path("urlPath");
		String rel = urlPath.path(URL_PATH_LANG).asString("").trim();
		if (rel.isBlank() && urlPath.isObject()) {
			for (JsonNode value : urlPath) {
				String candidate = value.asString("").trim();
				if (!candidate.isBlank()) {
					rel = candidate;
					break;
				}
			}
		}
		if (rel.isBlank()) {
			return campaign.campaignUrl();
		}
		if (rel.startsWith("http://") || rel.startsWith("https://")) {
			return rel;
		}
		return origin + (rel.startsWith("/") ? rel : "/" + rel);
	}

	private static String firstNonBlank(String... values) {
		for (String value : values) {
			if (value != null && !value.isBlank()) {
				return value;
			}
		}
		return "";
	}

	private static String origin(String url) {
		try {
			var uri = URI.create(url);
			if (uri.getScheme() != null && uri.getHost() != null) {
				var origin = new StringBuilder(uri.getScheme()).append("://").append(uri.getHost());
				if (uri.getPort() > 0) {
					origin.append(':').append(uri.getPort());
				}
				return origin.toString();
			}
		}
		catch (Exception ignored) {
			// fall through to the default origin
		}
		return DEFAULT_ORIGIN;
	}

	private static Instant parseInstant(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		try {
			return Instant.parse(raw);
		}
		catch (Exception e) {
			return null;
		}
	}

	private int computeDiscount(BigDecimal originalPrice, BigDecimal loungePrice) {
		if (originalPrice == null || loungePrice == null || originalPrice.signum() <= 0
				|| loungePrice.compareTo(originalPrice) >= 0) {
			return 0;
		}
		return originalPrice.subtract(loungePrice)
			.multiply(BigDecimal.valueOf(100))
			.divide(originalPrice, 0, java.math.RoundingMode.HALF_UP)
			.intValue();
	}

	/**
	 * Best-effort category inference from the (mostly German) product name; the catalog
	 * listing has no explicit category field usable by the domain filter.
	 */
	private Category inferCategory(String name) {
		if (name == null || name.isBlank()) {
			return Category.OTHER;
		}
		var n = name.toLowerCase(java.util.Locale.GERMAN);
		if (n.contains("jeans")) {
			return Category.JEANS;
		}
		if (n.contains("schuh") || n.contains("sneaker") || n.contains("stiefel") || n.contains("boot")
				|| n.contains("sandale") || n.contains("loafer") || n.contains("pump")) {
			return Category.SHOES;
		}
		if (n.contains("jacke") || n.contains("mantel") || n.contains("coat") || n.contains("parka")
				|| n.contains("weste")) {
			return Category.JACKETS;
		}
		if (n.contains("hose") || n.contains("chino") || n.contains("shorts") || n.contains("trouser")
				|| n.contains("leggings")) {
			return Category.TROUSERS;
		}
		if (n.contains("badehose") || n.contains("bikini") || n.contains("swim") || n.contains("badeanzug")) {
			return Category.SWIMWEAR;
		}
		if (n.contains("slip") || n.contains("boxer") || n.contains("unterwäsche") || n.contains("unterhose")
				|| n.contains("bh") || n.contains("socken") || n.contains("strumpf")) {
			return Category.UNDERWEAR;
		}
		if (n.contains("hemd") || n.contains("shirt") || n.contains("bluse") || n.contains("pullover")
				|| n.contains("pulli") || n.contains("sweat") || n.contains("top") || n.contains("polo")) {
			return Category.SHIRTS;
		}
		if (n.contains("gürtel") || n.contains("guertel") || n.contains("belt")) {
			return Category.BELTS;
		}
		return Category.OTHER;
	}

}
