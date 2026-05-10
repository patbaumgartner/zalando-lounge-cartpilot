package com.patbaumgartner.zalando.lounge.cartpilot.application;

import com.patbaumgartner.zalando.lounge.cartpilot.config.CartPilotProperties;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.Campaign;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.Decision;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.DiscoveredProduct;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.FilterResult;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.port.out.BrowserPort;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.port.out.DiscoveredProductPort;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.port.out.KnownBrandPort;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.port.out.NotificationPort;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.port.out.ProfilePort;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.port.out.PurchasedItemPort;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.service.ProductFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Orchestrates the daily Zalando Lounge scan (UC-01 + UC-02).
 *
 * Flow: 1. Ensure session is authenticated. 2. Fetch today's campaigns with retry. 3.
 * Scrape products for each campaign. 4. Persist discovered products & update brand
 * catalogue. 5. For each active profile: filter & score. 6. Dispatch AUTO_RESERVE items
 * to CartService. 7. Persist NOTIFY_ONLY items for the morning summary.
 */
@Service
public class CampaignScannerService {

	private static final Logger log = LoggerFactory.getLogger(CampaignScannerService.class);

	private final BrowserPort browser;

	private final ProfilePort profilePort;

	private final DiscoveredProductPort discoveredProductPort;

	private final KnownBrandPort knownBrandPort;

	private final PurchasedItemPort purchasedItemPort;

	private final ProductFilter filterService;

	private final CartService cartService;

	private final NotificationPort notification;

	private final CartPilotProperties properties;

	private final AtomicBoolean scanInProgress = new AtomicBoolean(false);

	public CampaignScannerService(BrowserPort browser, ProfilePort profilePort,
			DiscoveredProductPort discoveredProductPort, KnownBrandPort knownBrandPort,
			PurchasedItemPort purchasedItemPort, ProductFilter filterService, CartService cartService,
			NotificationPort notification, CartPilotProperties properties) {
		this.browser = browser;
		this.profilePort = profilePort;
		this.discoveredProductPort = discoveredProductPort;
		this.knownBrandPort = knownBrandPort;
		this.purchasedItemPort = purchasedItemPort;
		this.filterService = filterService;
		this.cartService = cartService;
		this.notification = notification;
		this.properties = properties;
	}

	public void scan() {
		if (!scanInProgress.compareAndSet(false, true)) {
			log.warn("Scan already in progress, skipping overlapping trigger");
			return;
		}

		try {
			log.atInfo().addArgument(() -> LocalDate.now()).log("Starting daily campaign scan for {}");

			// Probe the browser endpoint up front. A dead browser connection (no
			// context/page ever created) is a distinct failure from a rejected login and
			// previously surfaced only as an opaque "Login failed"; surface it clearly so
			// the
			// operator can tell a bad Patchright connection from a changed login page.
			try {
				browser.verifyBrowserAvailable();
			}
			catch (Exception browserUnavailable) {
				log.error("Aborting scan: browser endpoint is unavailable", browserUnavailable);
				notification.sendGroupMessage("🚨 Browser unavailable — " + browserUnavailable.getMessage());
				return;
			}

			if (!ensureAuthenticatedWithRetry()) {
				notification.sendGroupMessage("🚨 Login failed");
				return;
			}

			// Start every scan from a clean slate so leftover items from a previous run
			// never linger in the cart.
			try {
				var cleared = cartService.clearCart("auto-scan");
				if (cleared.browserRemovedCount() > 0 || cleared.reservationsUpdatedCount() > 0) {
					log.atInfo()
						.addArgument(cleared.browserRemovedCount())
						.addArgument(cleared.reservationsUpdatedCount())
						.log("Cleared cart before scan ({} item(s) removed, {} reservation(s) released)");
					notification.sendGroupMessage(
							"🧹 Cleared cart before scan — %d item(s)".formatted(cleared.browserRemovedCount()));
				}
			}
			catch (Exception e) {
				log.warn("Pre-scan cart clear failed: {}", e.getMessage());
			}

			var campaigns = fetchWithRetry();
			if (campaigns.isEmpty()) {
				notification.sendGroupMessage("ℹ️ No new campaigns today");
				return;
			}

			log.atInfo().addArgument(() -> campaigns.size()).log("Found {} campaign(s), scraping products...");
			notification.sendGroupMessage("🔍 Scanning " + campaigns.size() + " campaign(s)");
			var allProducts = scrapeAllProducts(campaigns);

			if (allProducts.isEmpty()) {
				log.info("No products found in today's campaigns");
				notification.sendGroupMessage("📭 No products found");
				return;
			}

			var persisted = discoveredProductPort.saveAll(allProducts);
			updateBrandCatalogue(persisted);

			var profiles = profilePort.findAllActive();
			log.atInfo()
				.addArgument(() -> persisted.size())
				.addArgument(() -> profiles.size())
				.log("Evaluating {} product(s) against {} active profile(s)");

			// Phase 1: cheap pre-filter (gender + price + brand tier) to find which
			// products are worth a detail-page size lookup. Campaign listings do not
			// expose sizes, so we defer the size gate.
			var purchasedByProfile = new java.util.HashMap<Long, Set<Long>>();
			var candidates = new java.util.LinkedHashSet<DiscoveredProduct>();
			for (var profile : profiles) {
				var purchased = purchasedItemPort.findProductIdsByProfileId(profile.id());
				purchasedByProfile.put(profile.id(), purchased);
				candidates.addAll(filterService.prefilterCandidates(persisted, profile, purchased));
			}

			// Phase 2: enrich only the candidates with sizes scraped from detail pages.
			notification
				.sendGroupMessage("📦 %d product(s) · %d candidate(s)".formatted(persisted.size(), candidates.size()));
			if (!candidates.isEmpty()) {
				log.atInfo().addArgument(candidates.size()).log("Fetching sizes for {} brand/price candidate(s)...");
				for (var candidate : candidates) {
					try {
						var details = browser.fetchProductDetails(candidate.productUrl());
						candidate.applyAvailableSizes(details.sizes());
						candidate.applyGender(details.gender());
					}
					catch (Exception e) {
						log.warn("Failed to fetch details for {}: {}", candidate.productUrl(), e.getMessage());
					}
				}
			}

			// Phase 3: full filter (size gate now satisfiable) and dispatch.
			int autoReserved = 0;
			int notifyOnly = 0;
			for (var profile : profiles) {
				var results = filterService.filter(persisted, profile, purchasedByProfile.get(profile.id()));

				for (var result : results) {
					if (result.decision() == Decision.AUTO_RESERVE) {
						autoReserved++;
					}
					else {
						notifyOnly++;
					}
					handleResult(result);
				}
			}

			persisted.forEach(p -> p.markProcessed());
			discoveredProductPort.saveAll(persisted);

			log.info("Scan complete");
			notification.sendGroupMessage("✅ Done — %d reserved · %d notify".formatted(autoReserved, notifyOnly));
		}
		catch (Exception e) {
			log.error("Scan failed", e);
			notification.sendGroupMessage("🚨 Scan failed — check logs");
		}
		finally {
			scanInProgress.set(false);
		}
	}

	// ── Private helpers ────────────────────────────────────────

	private boolean ensureAuthenticatedWithRetry() {
		try {
			browser.ensureAuthenticated();
			return true;
		}
		catch (Exception firstAttemptFailure) {
			log.warn("Authentication attempt 1/2 failed: {}", firstAttemptFailure.getMessage());
		}

		try {
			Thread.sleep(2_000L);
		}
		catch (InterruptedException interruptedException) {
			Thread.currentThread().interrupt();
			return false;
		}

		try {
			browser.ensureAuthenticated();
			return true;
		}
		catch (Exception secondAttemptFailure) {
			log.warn("Authentication attempt 2/2 failed: {}", secondAttemptFailure.getMessage());
			log.error("Authentication failed after retry", secondAttemptFailure);
			return false;
		}
	}

	private List<Campaign> fetchWithRetry() {
		int maxAttempts = properties.zalando().retryMaxAttempts();
		long baseIntervalMillis = properties.zalando().retryIntervalSeconds() * 1_000L;

		for (int attempt = 1; attempt <= maxAttempts; attempt++) {
			var campaigns = browser.fetchTodayCampaigns();
			if (!campaigns.isEmpty()) {
				return campaigns;
			}

			if (attempt < maxAttempts) {
				long exponential = baseIntervalMillis * (1L << Math.min(4, Math.max(0, attempt - 1)));
				long jitter = ThreadLocalRandom.current().nextLong(0, Math.max(1L, baseIntervalMillis / 2));
				long waitMillis = Math.min(300_000L, exponential + jitter);
				log.atInfo()
					.addArgument(attempt)
					.addArgument(maxAttempts)
					.addArgument(() -> waitMillis / 1000)
					.log("No campaigns on attempt {}/{}; retrying in {} s...");
				try {
					Thread.sleep(waitMillis);
				}
				catch (InterruptedException ie) {
					Thread.currentThread().interrupt();
					break;
				}
			}
			else {
				log.atInfo().addArgument(maxAttempts).log("No campaigns found after {} attempts");
			}
		}
		return List.of();
	}

	private List<DiscoveredProduct> scrapeAllProducts(List<Campaign> campaigns) {
		var products = new ArrayList<DiscoveredProduct>();
		for (var campaign : campaigns) {
			try {
				products.addAll(browser.scrapeProducts(campaign));
			}
			catch (Exception e) {
				log.error("Failed to scrape campaign {}: {}", campaign.campaignId(), e.getMessage(), e);
			}
		}
		return products;
	}

	private void updateBrandCatalogue(List<DiscoveredProduct> products) {
		var brands = products.stream().map(DiscoveredProduct::brand).distinct().toList();
		knownBrandPort.upsertAll(brands);
	}

	private void handleResult(FilterResult result) {
		if (result.decision() == Decision.AUTO_RESERVE) {
			log.atInfo()
				.addArgument(() -> result.product().name())
				.addArgument(() -> result.profile().name())
				.log("AUTO_RESERVE: {} for {}");
			cartService.addToCart(result);
		}
		else {
			log.atInfo()
				.addArgument(() -> result.product().name())
				.addArgument(() -> result.profile().name())
				.log("NOTIFY_ONLY: {} for {}");
			cartService.reserveForNotification(result);
		}
	}

}
