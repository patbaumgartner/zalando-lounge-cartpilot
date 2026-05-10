package com.patbaumgartner.zalando.lounge.cartpilot.adapter.out.browser;

import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitUntilState;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.Campaign;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.Category;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.DiscoveredProduct;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.Gender;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.ProductStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Scrapes campaigns and products from the Zalando Lounge SSR HTML.
 */
@Component
@Profile("!test")
class CampaignScraper {

	private static final Logger log = LoggerFactory.getLogger(CampaignScraper.class);

	private static final String INITIAL_STATE_SCRIPT = "() => JSON.stringify(window.__INITIAL_STATE__?.mylounge?.openCampaigns ?? [])";

	private static final String PRODUCT_CARD_SELECTOR = "[data-testid='lux-article-card']";

	private static final String CAMPAIGN_LINK_SELECTOR = "a[href*='/campaigns/'], a[href*='/event/']";

	private static final String CAMPAIGN_IMAGE_SELECTOR = "img[src*='/albums/']";

	private final ObjectMapper objectMapper;

	private final AuthenticationService authenticationService;

	CampaignScraper(ObjectMapper objectMapper, AuthenticationService authenticationService) {
		this.objectMapper = objectMapper;
		this.authenticationService = authenticationService;
	}

	List<Campaign> scrapeOpenCampaigns(Page page, String campaignUrl) {
		page.navigate(campaignUrl, new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
		page.waitForLoadState(LoadState.DOMCONTENTLOADED);
		authenticationService.acceptCookieBannerIfPresent(page);
		waitForInitialCampaignState(page);

		var raw = (String) page.evaluate(INITIAL_STATE_SCRIPT);

		var campaigns = parseCampaignsFromSsr(raw);
		if (!campaigns.isEmpty()) {
			return campaigns;
		}

		log.debug("No open campaigns in SSR state, trying DOM campaign link fallback");
		campaigns = scrapeCampaignsFromDom(page, campaignUrl);
		if (!campaigns.isEmpty()) {
			return campaigns;
		}

		log.debug("No DOM campaign links found, trying album-image campaign fallback");
		campaigns = scrapeCampaignsFromAlbumImages(page, campaignUrl);
		if (!campaigns.isEmpty()) {
			return campaigns;
		}

		log.warn("Campaign discovery returned empty; falling back to scanning landing page directly");
		return List.of(new Campaign("landing-page", "Landing Page", LocalDate.now(), campaignUrl));
	}

	List<DiscoveredProduct> scrapeProducts(Page page, Campaign campaign) {
		var url = campaign.campaignUrl().isBlank() ? "https://www.zalando-lounge.ch/event/" + campaign.campaignId()
				: campaign.campaignUrl();

		page.navigate(url, new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
		page.waitForLoadState(LoadState.DOMCONTENTLOADED);
		authenticationService.acceptCookieBannerIfPresent(page);
		waitForProductCards(page, campaign.campaignId());

		// Products are rendered in the DOM as article cards
		var productCards = page.querySelectorAll(PRODUCT_CARD_SELECTOR);
		var products = new ArrayList<DiscoveredProduct>();

		for (var card : productCards) {
			try {
				products.add(parseProductCard(card, campaign));
			}
			catch (Exception e) {
				log.error("Skipped unparseable product card: {}", e.getMessage(), e);
			}
		}

		log.atInfo()
			.addArgument(() -> products.size())
			.addArgument(campaign.campaignId())
			.log("Scraped {} products from campaign {}");
		return products;
	}

	// ── Private helpers ────────────────────────────────────────

	private void waitForInitialCampaignState(Page page) {
		try {
			page.waitForFunction("() => !!window.__INITIAL_STATE__?.mylounge?.openCampaigns",
					new Page.WaitForFunctionOptions().setTimeout(10_000));
		}
		catch (Exception e) {
			log.debug("Campaign initial state did not appear before timeout; continuing with best-effort scrape");
		}
	}

	private List<Campaign> parseCampaignsFromSsr(String raw) {
		if (raw == null || "[]".equals(raw)) {
			return List.of();
		}

		try {
			var node = objectMapper.readTree(raw);
			var today = LocalDate.now();
			var campaigns = new ArrayList<Campaign>();

			for (var campaignNode : node) {
				var startsAt = parseDate(campaignNode.path("startsAt").asString());
				if (startsAt != null && startsAt.equals(today)) {
					campaigns.add(new Campaign(campaignNode.path("id").asString(),
							campaignNode.path("title").asString(""), startsAt, campaignNode.path("url").asString("")));
				}
			}

			if (campaigns.isEmpty()) {
				log.debug("SSR campaign state parsed but no entries match today's date");
			}
			return campaigns;
		}
		catch (Exception e) {
			log.error("Failed to parse campaign SSR state: {}", e.getMessage(), e);
			return List.of();
		}
	}

	private List<Campaign> scrapeCampaignsFromDom(Page page, String campaignUrl) {
		var links = page.querySelectorAll(CAMPAIGN_LINK_SELECTOR);
		if (links.isEmpty()) {
			log.debug("No campaign links found in DOM fallback");
			return List.of();
		}

		LocalDate today = LocalDate.now();
		Set<String> seenIds = new LinkedHashSet<>();
		List<Campaign> campaigns = new ArrayList<>();

		for (var link : links) {
			try {
				String href = link.getAttribute("href");
				if (href == null || href.isBlank()) {
					continue;
				}

				String normalizedUrl = normalizeUrl(campaignUrl, href);
				String campaignId = extractCampaignId(normalizedUrl);
				if (campaignId == null || campaignId.isBlank() || !seenIds.add(campaignId)) {
					continue;
				}

				String title = (link.textContent() == null ? "" : link.textContent().trim());
				campaigns.add(new Campaign(campaignId, title, today, normalizedUrl));
			}
			catch (Exception ignored) {
				// Best-effort fallback; skip malformed links.
			}
		}

		log.info("DOM fallback discovered {} campaign link(s)", campaigns.size());
		return campaigns;
	}

	private List<Campaign> scrapeCampaignsFromAlbumImages(Page page, String campaignUrl) {
		var images = page.querySelectorAll(CAMPAIGN_IMAGE_SELECTOR);
		if (images.isEmpty()) {
			log.debug("No campaign album images found in fallback");
			return List.of();
		}

		LocalDate today = LocalDate.now();
		Set<String> seenIds = new LinkedHashSet<>();
		List<Campaign> campaigns = new ArrayList<>();

		for (var image : images) {
			try {
				String src = image.getAttribute("src");
				if (src == null || src.isBlank()) {
					continue;
				}

				String albumId = extractAlbumId(src);
				if (albumId == null || albumId.isBlank() || !seenIds.add(albumId)) {
					continue;
				}

				String albumCampaignUrl = normalizeUrl(campaignUrl, "/event/" + albumId);
				campaigns.add(new Campaign(albumId, "Album " + albumId.substring(0, Math.min(8, albumId.length())),
						today, albumCampaignUrl));
			}
			catch (Exception ignored) {
				// Best-effort fallback; skip malformed sources.
			}
		}

		log.info("Album-image fallback discovered {} campaign candidate(s)", campaigns.size());
		return campaigns;
	}

	private String normalizeUrl(String pageUrl, String href) {
		if (href.startsWith("http://") || href.startsWith("https://")) {
			return href;
		}
		if (href.startsWith("/")) {
			return "https://www.zalando-lounge.ch" + href;
		}
		int slashIndex = pageUrl.indexOf("/", "https://".length());
		String origin = slashIndex > 0 ? pageUrl.substring(0, slashIndex) : pageUrl;
		return origin + "/" + href;
	}

	private String extractCampaignId(String url) {
		String path = url;
		int queryIndex = path.indexOf('?');
		if (queryIndex >= 0) {
			path = path.substring(0, queryIndex);
		}

		String[] segments = path.split("/");
		for (int i = 0; i < segments.length - 1; i++) {
			if ("campaigns".equals(segments[i]) || "event".equals(segments[i])) {
				return segments[i + 1];
			}
		}
		return null;
	}

	private String extractAlbumId(String src) {
		String value = src;
		int queryIndex = value.indexOf('?');
		if (queryIndex >= 0) {
			value = value.substring(0, queryIndex);
		}

		String[] segments = value.split("/");
		for (int i = 0; i < segments.length - 1; i++) {
			if ("albums".equals(segments[i])) {
				return segments[i + 1];
			}
		}
		return null;
	}

	private void waitForProductCards(Page page, String campaignId) {
		try {
			// Product/campaign pages are SPAs whose background requests may never go
			// idle, so rely on DOM/content readiness (waitForSelector below) instead of
			// NETWORKIDLE.
			page.waitForLoadState(LoadState.DOMCONTENTLOADED);
			page.waitForSelector(PRODUCT_CARD_SELECTOR, new Page.WaitForSelectorOptions().setTimeout(10_000));
			var cards = page.querySelectorAll(PRODUCT_CARD_SELECTOR);
			log.debug("Product cards visible for campaign {}: {} found", campaignId, cards.size());
		}
		catch (Exception e) {
			log.debug("No product cards became visible for campaign {} before timeout; continuing: {}", campaignId,
					e.getMessage());
		}
	}

	@SuppressWarnings("unchecked")
	private DiscoveredProduct parseProductCard(ElementHandle card, Campaign campaign) {
		// The redesigned listing card uses styled-component hashed class names, so we
		// read
		// the few stable hooks (title link, lux-text name span, price markers) in one
		// in-page evaluation that is resilient to hash churn.
		var data = (java.util.Map<String, Object>) card.evaluate("""
				el => {
				  const a = el.querySelector('a[id$=\"-title-id\"]') || el.querySelector('a[href*=\"/articles/\"]');
				  const url = a ? a.getAttribute('href') : '';
				  let brand = '';
				  if (a) {
				    const styled = a.querySelector('span');
				    if (styled) {
				      brand = Array.from(styled.childNodes)
				        .filter(n => n.nodeType === 3)
				        .map(n => n.textContent)
				        .join('')
				        .trim();
				    }
				  }
				  const nameEl = el.querySelector('span.lux-text');
				  const name = nameEl ? nameEl.textContent.trim() : '';
				  const origEl = el.querySelector('[class*=\"RegularPriceLineThrough\"]');
				  const original = origEl ? origEl.textContent.trim() : '';
				  const redEl = el.querySelector('.text-function-100');
				  const reduced = redEl ? redEl.textContent.trim() : '';
				  return { brand, name, url, original, reduced };
				}
				""");

		var brand = str(data.get("brand"));
		var name = str(data.get("name"));
		var url = normalizeUrl("https://www.zalando-lounge.ch", str(data.get("url")));
		var originalPrice = parsePrice(str(data.get("original")));
		var loungePrice = parsePrice(str(data.get("reduced")));
		var discountPct = computeDiscount(originalPrice, loungePrice);

		return new DiscoveredProduct(null, campaign.campaignId(), brand.isEmpty() ? "Unknown" : brand,
				name.isEmpty() ? "Unknown" : name, inferCategory(name), Gender.UNISEX, List.of(), originalPrice,
				loungePrice, discountPct, url, ProductStatus.DISCOVERED, LocalDateTime.now());
	}

	private String str(Object value) {
		return value == null ? "" : value.toString().trim();
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
	 * Best-effort category inference from the German product name (listing has no
	 * category field).
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

	private BigDecimal parsePrice(String raw) {
		if (raw == null || raw.isBlank()) {
			return BigDecimal.ZERO;
		}
		var digits = raw.replaceAll("[^0-9.,]", "").replace(",", ".");
		try {
			return new BigDecimal(digits.isEmpty() ? "0" : digits);
		}
		catch (NumberFormatException e) {
			return BigDecimal.ZERO;
		}
	}

	private LocalDate parseDate(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		try {
			return LocalDate.parse(raw.substring(0, 10), DateTimeFormatter.ISO_LOCAL_DATE);
		}
		catch (Exception e) {
			return null;
		}
	}

}
