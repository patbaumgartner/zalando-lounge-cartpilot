package com.patbaumgartner.zalando.lounge.cartpilot.adapter.out.browser;

import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.LoadState;
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
import java.util.List;

/**
 * Scrapes campaigns and products from the Zalando Lounge SSR HTML.
 */
@Component
@Profile("!test")
class CampaignScraper {

	private static final Logger log = LoggerFactory.getLogger(CampaignScraper.class);

	private static final String INITIAL_STATE_SCRIPT = "() => JSON.stringify(window.__INITIAL_STATE__?.mylounge?.openCampaigns ?? [])";

	private final ObjectMapper objectMapper;

	CampaignScraper(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	List<Campaign> scrapeOpenCampaigns(Page page, String campaignUrl) {
		page.navigate(campaignUrl);
		page.waitForLoadState(LoadState.NETWORKIDLE);

		var raw = (String) page.evaluate(INITIAL_STATE_SCRIPT);
		if (raw == null || "[]".equals(raw)) {
			log.debug("No open campaigns in SSR state");
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
			return campaigns;
		}
		catch (Exception e) {
			log.error("Failed to parse campaign SSR state: {}", e.getMessage(), e);
			return List.of();
		}
	}

	List<DiscoveredProduct> scrapeProducts(Page page, Campaign campaign) {
		var url = campaign.campaignUrl().isBlank() ? "https://www.zalando-lounge.ch/event/" + campaign.campaignId()
				: campaign.campaignUrl();

		page.navigate(url);
		page.waitForLoadState(LoadState.NETWORKIDLE);

		// Products are rendered in the DOM as article cards
		var productCards = page.querySelectorAll("article[data-product-id], [data-testid='product-card']");
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

	private DiscoveredProduct parseProductCard(ElementHandle card, Campaign campaign) {
		String brand = text(card, "[data-testid='brand'], .brand-name");
		String name = text(card, "[data-testid='product-name'], .product-name");
		String category = attr(card, "[data-category]", "data-category");
		String gender = attr(card, "[data-gender]", "data-gender");
		String url = attr(card, "a[href]", "href");

		String originalPriceText = text(card, "[data-testid='original-price'], .original-price");
		String loungePriceText = text(card, "[data-testid='lounge-price'], .lounge-price");
		String discountText = text(card, "[data-testid='discount'], .discount");

		var sizes = card.querySelectorAll("[data-size], .size-option")
			.stream()
			.map(e -> e.textContent().trim())
			.filter(s -> !s.isEmpty())
			.toList();

		return new DiscoveredProduct(null, campaign.campaignId(), brand.isEmpty() ? "Unknown" : brand,
				name.isEmpty() ? "Unknown" : name, Category.fromString(category), parseGender(gender), sizes,
				parsePrice(originalPriceText), parsePrice(loungePriceText), parseDiscount(discountText), url,
				ProductStatus.DISCOVERED, LocalDateTime.now());
	}

	private String text(ElementHandle root, String selector) {
		var el = root.querySelector(selector);
		return el != null ? el.textContent().trim() : "";
	}

	private String attr(ElementHandle root, String selector, String attribute) {
		var el = root.querySelector(selector);
		if (el == null) {
			return "";
		}
		var val = el.getAttribute(attribute);
		return val != null ? val.trim() : "";
	}

	private Gender parseGender(String raw) {
		if (raw == null) {
			return Gender.UNISEX;
		}
		return switch (raw.toUpperCase()) {
			case "MEN", "MALE", "HERREN" -> Gender.MEN;
			case "WOMEN", "FEMALE", "DAMEN" -> Gender.WOMEN;
			case "KIDS", "KINDER" -> Gender.KIDS;
			default -> Gender.UNISEX;
		};
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

	private int parseDiscount(String raw) {
		if (raw == null || raw.isBlank()) {
			return 0;
		}
		var digits = raw.replaceAll("[^0-9]", "");
		try {
			return digits.isEmpty() ? 0 : Integer.parseInt(digits);
		}
		catch (NumberFormatException e) {
			return 0;
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
