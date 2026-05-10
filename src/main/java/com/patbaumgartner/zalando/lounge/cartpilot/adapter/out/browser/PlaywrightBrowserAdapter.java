package com.patbaumgartner.zalando.lounge.cartpilot.adapter.out.browser;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.LoadState;
import com.patbaumgartner.zalando.lounge.cartpilot.config.CartPilotProperties;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.Campaign;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.DiscoveredProduct;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.port.out.BrowserPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

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

	private static final String CART_URL = "https://www.zalando-lounge.ch/cart";

	private static final String ADD_TO_CART_BTN = "button[data-testid='add-to-cart'], button.add-to-cart";

	private static final String SIZE_SELECTOR = "[data-testid='size-selector'], select.size-select";

	private static final String CART_BADGE = "[data-testid='cart-count'], .cart-badge";

	private final Browser browser;

	private final AuthenticationService authService;

	private final CampaignScraper campaignScraper;

	private final CartPilotProperties properties;

	private BrowserContext context;

	public PlaywrightBrowserAdapter(Browser browser, AuthenticationService authService, CampaignScraper campaignScraper,
			CartPilotProperties properties) {
		this.browser = browser;
		this.authService = authService;
		this.campaignScraper = campaignScraper;
		this.properties = properties;
	}

	@Override
	public synchronized void ensureAuthenticated() {
		if (context == null) {
			context = browser.newContext();
		}
		authService.ensureAuthenticated(context);
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
	public boolean addToCart(String productUrl, String size) {
		try (var page = openPage()) {
			page.navigate(productUrl);
			page.waitForLoadState(LoadState.NETWORKIDLE);

			// Select size
			var sizeEl = page.querySelector(SIZE_SELECTOR);
			if (sizeEl == null) {
				log.warn("Size selector not found on {}", productUrl);
				return false;
			}
			sizeEl.selectOption(size);

			var prevBadge = cartBadgeCount(page);
			page.click(ADD_TO_CART_BTN);
			page.waitForLoadState(LoadState.NETWORKIDLE);

			var newBadge = cartBadgeCount(page);
			return newBadge > prevBadge;
		}
		catch (Exception e) {
			log.error("addToCart failed for {}: {}", productUrl, e.getMessage(), e);
			return false;
		}
	}

	@Override
	public void removeFromCart(String productUrl) {
		try (var page = openPage()) {
			page.navigate(CART_URL);
			page.waitForLoadState(LoadState.NETWORKIDLE);

			// Find the item by its product link and click the remove button
			var removeBtn = page
				.querySelector("a[href*='%s'] ~ button[data-testid='remove'], a[href*='%s'] + .remove-btn"
					.formatted(productUrl, productUrl));
			if (removeBtn != null) {
				removeBtn.click();
				page.waitForLoadState(LoadState.NETWORKIDLE);
			}
			else {
				log.warn("Remove button not found for {}", productUrl);
			}
		}
		catch (Exception e) {
			log.error("removeFromCart failed for {}: {}", productUrl, e.getMessage(), e);
		}
	}

	@Override
	public boolean isItemInCart(String productUrl) {
		try (var page = openPage()) {
			page.navigate(CART_URL);
			page.waitForLoadState(LoadState.NETWORKIDLE);
			return page.querySelector("a[href*='%s']".formatted(productUrl)) != null;
		}
		catch (Exception e) {
			log.error("isItemInCart failed for {}: {}", productUrl, e.getMessage(), e);
			return false;
		}
	}

	// ── Private helpers ────────────────────────────────────────

	private Page openPage() {
		if (context == null) {
			context = browser.newContext();
		}
		return context.newPage();
	}

	private int cartBadgeCount(Page page) {
		try {
			var badge = page.querySelector(CART_BADGE);
			if (badge == null) {
				return 0;
			}
			var text = badge.textContent().replaceAll("[^0-9]", "");
			return text.isEmpty() ? 0 : Integer.parseInt(text);
		}
		catch (Exception e) {
			return 0;
		}
	}

}
