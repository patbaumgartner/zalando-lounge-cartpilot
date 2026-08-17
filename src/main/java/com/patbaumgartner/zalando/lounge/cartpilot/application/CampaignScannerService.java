package com.patbaumgartner.zalando.lounge.cartpilot.application;

import com.patbaumgartner.zalando.lounge.cartpilot.config.CartPilotProperties;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.Campaign;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.Decision;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.DiscoveredProduct;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.Profile;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.ReservationStatus;
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

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
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

	private final BrowserGate browserGate;

	private final AtomicBoolean scanInProgress = new AtomicBoolean(false);

	public CampaignScannerService(BrowserPort browser, ProfilePort profilePort,
			DiscoveredProductPort discoveredProductPort, KnownBrandPort knownBrandPort,
			PurchasedItemPort purchasedItemPort, ProductFilter filterService, CartService cartService,
			NotificationPort notification, CartPilotProperties properties, BrowserGate browserGate) {
		this.browser = browser;
		this.profilePort = profilePort;
		this.discoveredProductPort = discoveredProductPort;
		this.knownBrandPort = knownBrandPort;
		this.purchasedItemPort = purchasedItemPort;
		this.filterService = filterService;
		this.cartService = cartService;
		this.notification = notification;
		this.properties = properties;
		this.browserGate = browserGate;
	}

	public void scan() {
		if (!scanInProgress.compareAndSet(false, true)) {
			log.warn("Scan already in progress, skipping overlapping trigger");
			return;
		}
		try {
			browserGate.runExclusively("daily scan", this::runScan);
		}
		finally {
			scanInProgress.set(false);
		}
	}

	private void runScan() {
		var startedAt = Instant.now();
		var notes = new ArrayList<String>();
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
				notification.sendGroupMessage("🚨 Login failed — session could not be established after %d attempt(s)"
					.formatted(loginAttempts()));
				return;
			}

			var campaigns = fetchWithRetry();
			if (campaigns.isEmpty()) {
				notification.sendGroupMessage("ℹ️ No new campaigns today (after %d attempt(s), %s)"
					.formatted(properties.zalando().retryMaxAttempts(), elapsed(startedAt)));
				return;
			}

			log.atInfo().addArgument(() -> campaigns.size()).log("Found {} campaign(s), scraping products...");
			notification.sendGroupMessage("🔍 Scanning " + campaigns.size() + " campaign(s)");
			var allProducts = scrapeAllProducts(campaigns, notes);

			if (allProducts.isEmpty()) {
				log.info("No products found in today's campaigns");
				notification.sendGroupMessage("📭 No products found in %d campaign(s)%s".formatted(campaigns.size(),
						notes.isEmpty() ? "" : " — " + String.join("; ", notes)));
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
			var purchasedByProfile = new java.util.HashMap<Long, Set<String>>();
			var candidates = new java.util.LinkedHashSet<DiscoveredProduct>();
			for (var profile : profiles) {
				var purchased = purchasedItemPort.findPurchasedArticleKeysByProfileId(profile.id());
				purchasedByProfile.put(profile.id(), purchased);
				candidates.addAll(filterService.prefilterCandidates(persisted, profile, purchased));
			}

			// Phase 2: enrich only the candidates with sizes scraped from detail pages.
			notification
				.sendGroupMessage("📦 %d product(s) · %d candidate(s)".formatted(persisted.size(), candidates.size()));
			int detailFetches = enrichCandidateSizes(candidates, notes);

			// Phase 3: free the basket for today's matches, then filter and dispatch.
			// Clearing only once there is something to put back means a failed login,
			// a dead browser or an empty campaign list can no longer throw away
			// yesterday's still-valid holds for nothing.
			clearCartBeforeDispatch(notes);
			var outcome = dispatchMatches(profiles, persisted, purchasedByProfile);

			persisted.forEach(p -> p.markProcessed());
			discoveredProductPort.saveAll(persisted);

			log.info("Scan complete");
			publishScanResults(new ScanContext(campaigns.size(), persisted.size(), candidates.size(), detailFetches,
					profiles.size(), Duration.between(startedAt, Instant.now()), notes), outcome);
		}
		catch (Exception e) {
			log.error("Scan failed", e);
			notification.sendGroupMessage("🚨 Scan failed — %s: %s".formatted(e.getClass().getSimpleName(),
					e.getMessage() == null ? "no message" : e.getMessage()));
		}
	}

	// ── Private helpers ────────────────────────────────────────

	private int loginAttempts() {
		return Math.max(1, properties.zalando().loginMaxAttempts());
	}

	/**
	 * Empties the basket immediately before today's matches are added, so a scan starts
	 * from a clean slate without discarding existing holds when it turns out there is
	 * nothing to scan. An unreadable basket releases nothing and is reported as a note.
	 */
	private void clearCartBeforeDispatch(List<String> notes) {
		try {
			var cleared = cartService.clearCart("auto-scan");
			if (!cleared.cartReadable()) {
				log.warn("Pre-dispatch cart clear could not read the basket");
				notes.add("Cart could not be read before dispatch — previous holds were left untouched");
				return;
			}
			if (cleared.browserRemovedCount() > 0 || cleared.reservationsUpdatedCount() > 0) {
				log.atInfo()
					.addArgument(cleared.browserRemovedCount())
					.addArgument(cleared.reservationsUpdatedCount())
					.log("Cleared cart before dispatch ({} item(s) removed, {} reservation(s) released)");
				notification.sendGroupMessage(
						"🧹 Cleared cart before dispatch — %d item(s)".formatted(cleared.browserRemovedCount()));
			}
		}
		catch (Exception e) {
			log.warn("Pre-dispatch cart clear failed: {}", e.getMessage());
			notes.add("Pre-dispatch cart clear failed: " + e.getMessage());
		}
	}

	private int enrichCandidateSizes(Collection<DiscoveredProduct> candidates, List<String> notes) {
		if (candidates.isEmpty()) {
			return 0;
		}

		log.atInfo().addArgument(candidates.size()).log("Fetching sizes for {} brand/price candidate(s)...");
		int detailFetches = 0;
		int failures = 0;
		for (var candidate : candidates) {
			// The catalog listing already populated sizes/gender, so only fall
			// back to a per-article detail fetch for the rare candidate with no
			// sizes yet. This avoids hundreds of redundant (and rate-limited)
			// detail calls on a full scan of every open campaign.
			if (!candidate.sizesAvailable().isEmpty()) {
				continue;
			}
			try {
				detailFetches++;
				var details = browser.fetchProductDetails(candidate.productUrl());
				if (!details.sizes().isEmpty()) {
					candidate.applyAvailableSizes(details.sizes());
					candidate.applyGender(details.gender());
				}
			}
			catch (Exception e) {
				failures++;
				log.warn("Failed to fetch details for {}: {}", candidate.productUrl(), e.getMessage());
			}
		}
		if (failures > 0) {
			notes.add("%d detail lookup(s) failed".formatted(failures));
		}
		return detailFetches;
	}

	private DispatchOutcome dispatchMatches(List<Profile> profiles, List<DiscoveredProduct> persisted,
			Map<Long, Set<String>> purchasedByProfile) {
		var reserved = new ArrayList<NotificationPort.ProductLink>();
		var blocked = new ArrayList<NotificationPort.ProductLink>();
		var unavailable = new ArrayList<NotificationPort.ProductLink>();
		var failed = new ArrayList<NotificationPort.ProductLink>();
		var notifyOnly = new ArrayList<NotificationPort.ProductLink>();
		boolean cartBlockedForScan = false;

		for (var profile : profiles) {
			for (var result : filterService.filter(persisted, profile, purchasedByProfile.get(profile.id()))) {
				if (result.decision() != Decision.AUTO_RESERVE) {
					log.atInfo()
						.addArgument(() -> result.product().name())
						.addArgument(() -> result.profile().name())
						.log("NOTIFY_ONLY: {} for {}");
					cartService.reserveForNotification(result);
					notifyOnly.add(NotificationPort.ProductLink.of(result, ReservationStatus.PENDING, "notify only"));
					continue;
				}

				if (cartBlockedForScan) {
					log.atInfo()
						.addArgument(() -> result.product().name())
						.addArgument(() -> result.profile().name())
						.log("NOTIFY_ONLY after cart bot protection: {} for {}");
					cartService.reserveForNotification(result);
					notifyOnly.add(NotificationPort.ProductLink.of(result, ReservationStatus.PENDING,
							"cart blocked for this scan"));
					continue;
				}

				log.atInfo()
					.addArgument(() -> result.product().name())
					.addArgument(() -> result.profile().name())
					.log("AUTO_RESERVE: {} for {}");
				var addResult = cartService.addToCart(result);
				switch (addResult.outcome()) {
					case ADDED ->
						reserved.add(NotificationPort.ProductLink.of(result, ReservationStatus.IN_CART, "reserved"));
					case BLOCKED -> {
						cartBlockedForScan = true;
						blocked.add(NotificationPort.ProductLink.of(result, ReservationStatus.BLOCKED,
								addResult.describe()));
					}
					case SIZE_UNAVAILABLE -> unavailable.add(NotificationPort.ProductLink.of(result,
							ReservationStatus.OUT_OF_STOCK, addResult.detail()));
					case FAILED -> failed
						.add(NotificationPort.ProductLink.of(result, ReservationStatus.FAILED, addResult.describe()));
				}
			}
		}
		return new DispatchOutcome(reserved, blocked, unavailable, failed, notifyOnly);
	}

	/**
	 * Posts the diagnostics first, then one link list per outcome. Reserved items are
	 * listed alongside the rest so the group still has a working link once the cart hold
	 * runs out, and blocked items are listed because only a human can still grab them.
	 */
	private void publishScanResults(ScanContext context, DispatchOutcome outcome) {
		notification.sendScanReport(new NotificationPort.ScanReport(LocalDate.now(), context.duration(),
				context.campaignCount(), context.productCount(), context.candidateCount(), context.detailFetchCount(),
				context.activeProfileCount(), outcome.matchCount(), outcome.reserved().size(), outcome.blocked().size(),
				outcome.unavailable().size(), outcome.failed().size(), outcome.notifyOnly().size(), context.notes()));

		if (outcome.matchCount() == 0) {
			return;
		}

		sendLinksIfAny("Reserved — keep an eye on the expiry", outcome.reserved());
		sendLinksIfAny("Blocked by bot protection — grab these manually", outcome.blocked());
		sendLinksIfAny("Matched, notify only", outcome.notifyOnly());
		sendLinksIfAny("Matched but size no longer purchasable", outcome.unavailable());
		sendLinksIfAny("Matched but the cart add failed", outcome.failed());
	}

	private void sendLinksIfAny(String heading, List<NotificationPort.ProductLink> entries) {
		if (!entries.isEmpty()) {
			notification.sendProductLinks(heading, entries);
		}
	}

	private static String elapsed(Instant startedAt) {
		return Duration.between(startedAt, Instant.now()).toSeconds() + " s";
	}

	private record ScanContext(int campaignCount, int productCount, int candidateCount, int detailFetchCount,
			int activeProfileCount, Duration duration, List<String> notes) {
	}

	private record DispatchOutcome(List<NotificationPort.ProductLink> reserved,
			List<NotificationPort.ProductLink> blocked, List<NotificationPort.ProductLink> unavailable,
			List<NotificationPort.ProductLink> failed, List<NotificationPort.ProductLink> notifyOnly) {

		int matchCount() {
			return reserved.size() + blocked.size() + unavailable.size() + failed.size() + notifyOnly.size();
		}
	}

	private boolean ensureAuthenticatedWithRetry() {
		int attempts = loginAttempts();
		long baseDelayMillis = Math.max(0L, properties.zalando().authRetryBaseDelayMs());

		for (int attempt = 1; attempt <= attempts; attempt++) {
			try {
				browser.ensureAuthenticated();
				return true;
			}
			catch (Exception failure) {
				log.warn("Authentication attempt {}/{} failed: {}", attempt, attempts, failure.getMessage());
				if (attempt == attempts) {
					log.error("Authentication failed after {} attempt(s)", attempts, failure);
					return false;
				}
				if (!sleepQuietly(backoffWithJitter(baseDelayMillis, attempt))) {
					return false;
				}
			}
		}
		return false;
	}

	private List<Campaign> fetchWithRetry() {
		int maxAttempts = Math.max(1, properties.zalando().retryMaxAttempts());
		long baseIntervalMillis = properties.zalando().retryIntervalSeconds() * 1_000L;

		for (int attempt = 1; attempt <= maxAttempts; attempt++) {
			List<Campaign> campaigns;
			try {
				campaigns = browser.fetchTodayCampaigns();
			}
			catch (Exception e) {
				// A dropped websocket or a transient bot wall used to abort the whole
				// scan here, because only an empty result was ever retried.
				log.warn("Campaign fetch attempt {}/{} failed: {}", attempt, maxAttempts, e.getMessage());
				campaigns = List.of();
			}
			if (!campaigns.isEmpty()) {
				return campaigns;
			}

			if (attempt < maxAttempts) {
				long waitMillis = backoffWithJitter(baseIntervalMillis, attempt);
				log.atInfo()
					.addArgument(attempt)
					.addArgument(maxAttempts)
					.addArgument(() -> waitMillis / 1000)
					.log("No campaigns on attempt {}/{}; retrying in {} s...");
				if (!sleepQuietly(waitMillis)) {
					break;
				}
			}
			else {
				log.atInfo().addArgument(maxAttempts).log("No campaigns found after {} attempts");
			}
		}
		return List.of();
	}

	/**
	 * Exponential backoff capped at five minutes, with jitter to avoid lockstep retries.
	 */
	private static long backoffWithJitter(long baseMillis, int attempt) {
		if (baseMillis <= 0) {
			return 0L;
		}
		long exponential = baseMillis * (1L << Math.min(4, Math.max(0, attempt - 1)));
		long jitter = ThreadLocalRandom.current().nextLong(0, Math.max(1L, baseMillis / 2));
		return Math.min(300_000L, exponential + jitter);
	}

	/**
	 * Sleeps, returning {@code false} when the thread was interrupted meanwhile.
	 */
	private static boolean sleepQuietly(long millis) {
		if (millis <= 0) {
			return true;
		}
		try {
			Thread.sleep(millis);
			return true;
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	private List<DiscoveredProduct> scrapeAllProducts(List<Campaign> campaigns, List<String> notes) {
		var products = new ArrayList<DiscoveredProduct>();
		for (var campaign : campaigns) {
			try {
				products.addAll(browser.scrapeProducts(campaign));
			}
			catch (Exception e) {
				log.error("Failed to scrape campaign {}: {}", campaign.campaignId(), e.getMessage(), e);
				notes.add("Campaign %s failed to scrape: %s".formatted(campaign.campaignId(), e.getMessage()));
			}
		}
		return products;
	}

	private void updateBrandCatalogue(List<DiscoveredProduct> products) {
		var brands = products.stream().map(DiscoveredProduct::brand).distinct().toList();
		knownBrandPort.upsertAll(brands);
	}

}
