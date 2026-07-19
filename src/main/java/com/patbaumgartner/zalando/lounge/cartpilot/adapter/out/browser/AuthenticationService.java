package com.patbaumgartner.zalando.lounge.cartpilot.adapter.out.browser;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitUntilState;
import com.patbaumgartner.zalando.lounge.cartpilot.config.CartPilotProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Manages Zalando Lounge session state via Playwright's storage state API. Credentials
 * are read exclusively from environment variables — never hardcoded.
 */
@Service
@Profile("!test")
public class AuthenticationService {

	private static final Logger log = LoggerFactory.getLogger(AuthenticationService.class);

	private static final String EMAIL_SELECTOR = "input[type='email']:not([aria-hidden='true']):not([tabindex='-1']), input[name='email']:not([aria-hidden='true']):not([tabindex='-1'])";

	private static final String LOGIN_ENTRY_SELECTOR = "#topbar-cta-btn";

	private static final String EMAIL_CONTINUE_SELECTOR = "button[data-testid='verify-email-button'], button[type='submit'], button:has-text('Weiter'), button:has-text('Continue'), button:has-text('Next')";

	// Zalando's SSO renders the password step inside an off-screen section that
	// carries aria-disabled='true' until the backend activates it. The field is
	// only genuinely interactable once it is on-screen and natively enabled.
	private static final String PASSWORD_STEP_READY_JS = """
			() => {
			  const p = document.querySelector('#password');
			  if (!p || p.disabled) return false;
			  const r = p.getBoundingClientRect();
			  return r.x >= 0 && r.y >= 0 && r.width > 0;
			}
			""";

	private static final List<String> COOKIE_ACCEPT_SELECTORS = List.of("button[data-testid='uc-accept-all-button']");

	private final CartPilotProperties properties;

	private final Environment environment;

	private final double navigationTimeoutMs;

	private final double sessionCheckTimeoutMs;

	private final double loginNavigationTimeoutMs;

	private final double loginPostSubmitTimeoutMs;

	private final double elementTimeoutMs;

	private final int loginMaxAttempts;

	private final long authRetryBaseDelayMs;

	private final boolean headedLoginFallbackEnabled;

	private final double headedLoginTimeoutMs;

	private final boolean networkDiagnosticsEnabled;

	private final boolean trustSessionFileInDev;

	private final Path diagnosticsDir;

	private final String loginUrl;

	private final String sessionCheckUrl;

	private final String homeUrl;

	public AuthenticationService(CartPilotProperties properties, Environment environment) {
		this.properties = properties;
		this.environment = environment;
		this.navigationTimeoutMs = properties.zalando().navigationTimeoutMs();
		this.sessionCheckTimeoutMs = configuredTimeout(properties.zalando().sessionCheckTimeoutMs(), 12_000L);
		this.loginNavigationTimeoutMs = configuredTimeout(properties.zalando().loginNavigationTimeoutMs(),
				Math.round(navigationTimeoutMs));
		this.loginPostSubmitTimeoutMs = configuredTimeout(properties.zalando().loginPostSubmitTimeoutMs(), 30_000L);
		this.elementTimeoutMs = properties.zalando().elementTimeoutMs();
		this.loginMaxAttempts = properties.zalando().loginMaxAttempts();
		this.authRetryBaseDelayMs = Math.max(250L, properties.zalando().authRetryBaseDelayMs());
		this.headedLoginFallbackEnabled = properties.zalando().headedLoginFallbackEnabled();
		this.headedLoginTimeoutMs = properties.zalando().headedLoginTimeoutMs();
		this.networkDiagnosticsEnabled = properties.zalando().networkDiagnosticsEnabled();
		this.trustSessionFileInDev = properties.zalando().trustSessionFileInDev();
		this.diagnosticsDir = Path.of(properties.zalando().diagnosticsDir());
		this.loginUrl = joinUrl(properties.zalando().baseUrl(), "login");
		this.sessionCheckUrl = joinUrl(properties.zalando().baseUrl(), "event");
		this.homeUrl = properties.zalando().baseUrl();
	}

	public void ensureAuthenticated(BrowserContext context) {
		if (hasValidSession()) {
			try {
				if (isSessionValidStaged(context)) {
					log.debug("Session loaded from file and passed staged validation");
					return;
				}
				log.info("Saved session expired, re-authenticating...");
			}
			catch (Exception e) {
				log.error("Failed to load session, re-authenticating: {}", e.getMessage(), e);
			}
		}
		login(context);
	}

	public int authContextResetRetries() {
		return Math.max(0, properties.zalando().authContextResetRetries());
	}

	public boolean shouldResetContext(Throwable error) {
		var category = classifyFailure(error);
		return category == AuthFailureCategory.NETWORK || category == AuthFailureCategory.TIMEOUT
				|| category == AuthFailureCategory.CHALLENGE || category == AuthFailureCategory.BROWSER_UNAVAILABLE;
	}

	private boolean hasValidSession() {
		Path sessionPath = Path.of(properties.zalando().sessionFile());
		return Files.exists(sessionPath) && sessionPath.toFile().length() > 0;
	}

	private boolean isSessionValidStaged(BrowserContext context) {
		boolean cookieSignal = hasAuthenticationCookies(context);

		if (!cookieSignal) {
			log.info("Session validation stage A failed: no authentication cookies found");
			return false;
		}

		if (trustSessionFileInDev && isDevProfileActive()) {
			log.warn("Trusting persisted session in dev mode because trust-session-file-in-dev=true");
			return true;
		}

		if (!isSessionValidByProbe(context)) {
			return false;
		}

		// Stage B only checks the final URL, but Zalando Lounge serves the public
		// landing page at /event without redirecting logged-out visitors to /login,
		// so an expired session still passes. Stage C asks the cart API — it returns
		// JSON only for authenticated sessions (logged-out requests get an HTML
		// login/interstitial page instead).
		return isSessionValidByCartApi(context);
	}

	private boolean isDevProfileActive() {
		for (String profile : environment.getActiveProfiles()) {
			if ("dev".equals(profile)) {
				return true;
			}
		}
		return false;
	}

	private boolean hasAuthenticationCookies(BrowserContext context) {
		try {
			var cookies = context.cookies(properties.zalando().baseUrl());
			if (cookies == null || cookies.isEmpty()) {
				return false;
			}

			Instant now = Instant.now();
			return cookies.stream().anyMatch(cookie -> {
				if (cookie.value == null || cookie.value.isBlank()) {
					return false;
				}
				var name = cookie.name == null ? "" : cookie.name.toLowerCase(Locale.ROOT);
				boolean authLike = name.contains("sess") || name.contains("auth") || name.contains("token")
						|| name.contains("jwt") || name.contains("customer") || name.contains("identity");
				if (!authLike) {
					return false;
				}

				double expires = cookie.expires;
				if (expires <= 0) {
					return true;
				}
				long epochSeconds = (long) Math.floor(expires);
				return now.isBefore(Instant.ofEpochSecond(epochSeconds));
			});
		}
		catch (Exception e) {
			log.debug("Could not inspect cookies during session validation: {}", e.getMessage());
			return false;
		}
	}

	private boolean isSessionValidByProbe(BrowserContext context) {
		try (var page = context.newPage()) {
			attachNetworkDiagnostics(page, "session-check");
			page.navigate(sessionCheckUrl,
					new com.microsoft.playwright.Page.NavigateOptions().setWaitUntil(WaitUntilState.COMMIT)
						.setTimeout(sessionCheckTimeoutMs));
			page.waitForLoadState(LoadState.DOMCONTENTLOADED);
			return isAuthenticatedDestination(page.url());
		}
		catch (Exception e) {
			log.info("Session validation stage B probe failed: {}", e.getMessage());
			return false;
		}
	}

	/**
	 * Authoritative session check: the cart API returns JSON for authenticated sessions
	 * and an HTML login/interstitial page otherwise. A URL-based probe cannot detect this
	 * because Zalando Lounge does not redirect logged-out visitors.
	 */
	private boolean isSessionValidByCartApi(BrowserContext context) {
		try {
			var response = context.request()
				.get(properties.zalando().cartApiUrl(),
						com.microsoft.playwright.options.RequestOptions.create()
							.setTimeout(sessionCheckTimeoutMs)
							.setHeader("Accept", "application/json"));
			try {
				if (!response.ok()) {
					log.info("Session validation stage C failed: cart API returned status {}", response.status());
					return false;
				}
				String body = response.text() == null ? "" : response.text().strip();
				boolean looksLikeJson = body.startsWith("{") || body.startsWith("[");
				if (!looksLikeJson) {
					log.info("Session validation stage C failed: cart API returned non-JSON (logged-out) response");
					return false;
				}
				return true;
			}
			finally {
				response.dispose();
			}
		}
		catch (Exception e) {
			log.info("Session validation stage C probe failed: {}", e.getMessage());
			return false;
		}
	}

	private void login(BrowserContext context) {
		validateCredentials();
		log.info("Logging in to Zalando Lounge...");
		warmUpSession(context);
		Exception lastError = null;
		AuthFailureCategory lastCategory = AuthFailureCategory.UNKNOWN;

		for (int attempt = 1; attempt <= loginMaxAttempts; attempt++) {
			Page page = null;
			try {
				page = context.newPage();
				attachNetworkDiagnostics(page, "auto-login-" + attempt);
				log.debug("Login attempt {}/{}: navigating to login page", attempt, loginMaxAttempts);
				page.navigate(loginUrl,
						new com.microsoft.playwright.Page.NavigateOptions().setWaitUntil(WaitUntilState.COMMIT)
							.setTimeout(loginNavigationTimeoutMs));
				acceptCookieBannerIfPresent(page);
				openLoginFormIfNeeded(page);

				page.waitForSelector(EMAIL_SELECTOR,
						new com.microsoft.playwright.Page.WaitForSelectorOptions().setTimeout(elementTimeoutMs));

				// The login page redirects to accounts.zalando.com, which runs its
				// own Akamai sensor independent of the lounge homepage warm-up. Let
				// that sensor observe genuine interaction and post valid sensor data
				// before we submit the email, otherwise the verify-email call comes
				// back with a generic "something went wrong" bot-wall error.
				warmUpAuthPageSensor(page);

				// Zalando's bot wall (Akamai) rejects instant value injection with a
				// generic "something went wrong" error. Drive the form with real
				// keystrokes/clicks and human-like pauses so the sensor data clears.
				typeHumanLike(page, EMAIL_SELECTOR, properties.zalando().email());
				humanPause();
				clickHumanLike(page, EMAIL_CONTINUE_SELECTOR);

				// The password step lives in an off-screen, aria-disabled section
				// until Zalando's backend verifies the email and activates it.
				// Wait for it to become on-screen and natively enabled before typing.
				waitForPasswordStepReady(page);
				humanPause();
				typePasswordHumanLike(page, properties.zalando().password());
				humanPause();
				// Submit via Enter: the login button is duplicated off-screen, so a
				// keyboard submit from the focused field is more reliable than a click.
				page.keyboard().press("Enter");

				page.waitForLoadState(LoadState.DOMCONTENTLOADED);
				page.waitForURL(this::isAuthenticatedDestination,
						new com.microsoft.playwright.Page.WaitForURLOptions().setTimeout(loginPostSubmitTimeoutMs));

				if (!isAuthenticatedDestination(page.url())) {
					throw new LoginFailedException(
							"Login failed - not redirected to authenticated Zalando Lounge page");
				}
				if (!isSessionValidByProbe(context)) {
					throw new LoginFailedException("Login flow finished but authenticated session probe failed");
				}

				saveSessionAtomically(context);
				log.info("Login successful on attempt {}/{}, session saved", attempt, loginMaxAttempts);
				return;
			}
			catch (Exception e) {
				lastError = e;
				lastCategory = classifyFailure(e);
				var diagnosticPath = captureDiagnostics(context, page, "auto-login-" + attempt, e);
				log.warn("Login attempt {}/{} failed [{}]: {}", attempt, loginMaxAttempts, lastCategory,
						e.getMessage());
				if (diagnosticPath != null) {
					log.warn("Authentication diagnostics stored at {}", diagnosticPath);
				}

				if (lastCategory == AuthFailureCategory.CHALLENGE && headedLoginFallbackEnabled
						&& !properties.zalando().headless()) {
					log.warn("Detected potential challenge/WAF flow, escalating directly to headed fallback login");
					break;
				}

				if (attempt < loginMaxAttempts) {
					sleepWithJitter(attempt);
				}
			}
			finally {
				if (page != null) {
					try {
						page.close();
					}
					catch (Exception ignored) {
					}
				}
			}
		}

		if (headedLoginFallbackEnabled) {
			runHeadedFallbackLogin(context, lastError);
			return;
		}

		// Preserve the warmed Akamai cookies (the context's storage state) rather than
		// deleting them, so trust accumulates across retries and restarts. A stale auth
		// session left behind is harmless: staged validation re-checks it and
		// re-logs-in.
		saveSessionAtomically(context);
		throw new LoginFailedException(
				"Login failed after " + loginMaxAttempts + " attempts"
						+ (lastError != null ? " [" + lastCategory + "]: " + lastError.getMessage() : ""),
				lastCategory, null);
	}

	private void runHeadedFallbackLogin(BrowserContext context, Exception lastError) {
		if (properties.zalando().headless()) {
			throw new LoginFailedException(
					"Headed fallback login is enabled but PLAYWRIGHT_HEADLESS=true. Set PLAYWRIGHT_HEADLESS=false to use manual login fallback.");
		}

		log.warn("Automatic login failed, switching to manual headed fallback{}",
				lastError == null ? "" : " (reason: " + lastError.getMessage() + ")");

		Page page = null;
		try {
			page = context.newPage();
			attachNetworkDiagnostics(page, "manual-login");
			page.navigate(loginUrl,
					new com.microsoft.playwright.Page.NavigateOptions().setWaitUntil(WaitUntilState.COMMIT)
						.setTimeout(loginNavigationTimeoutMs));
			acceptCookieBannerIfPresent(page);

			log.warn(
					"Complete login manually in the opened browser window within {} seconds; session state will be saved automatically.",
					Math.round(headedLoginTimeoutMs / 1000.0));

			page.waitForURL(this::isAuthenticatedDestination,
					new com.microsoft.playwright.Page.WaitForURLOptions().setTimeout(headedLoginTimeoutMs));
			page.waitForLoadState(LoadState.DOMCONTENTLOADED);

			if (!isSessionValidByProbe(context)) {
				throw new LoginFailedException("Manual login completed but resulting session is invalid");
			}

			saveSessionAtomically(context);
			log.info("Manual headed login completed successfully and session state was saved");
		}
		catch (Exception e) {
			clearCachedSession();
			var category = classifyFailure(e);
			throw new LoginFailedException("Manual headed fallback login failed: " + e.getMessage(), category,
					captureDiagnostics(context, page, "manual-login-failed", e));
		}
		finally {
			if (page != null) {
				try {
					page.close();
				}
				catch (Exception ignored) {
				}
			}
		}
	}

	public void acceptCookieBannerIfPresent(Page page) {
		log.debug("Attempting to dismiss cookie banner...");
		page.waitForTimeout(3000); // Wait for cookie banner to appear
		for (String selector : COOKIE_ACCEPT_SELECTORS) {
			try {
				var button = page.locator(selector).first();
				if (button.isVisible()) {
					log.debug("Found cookie banner button: {}", selector);
					moveMouseTo(page, button);
					button.click(new com.microsoft.playwright.Locator.ClickOptions().setTimeout(3000));
					page.waitForTimeout(500); // Give banner time to close
					log.debug("Accepted cookie banner with selector: {}", selector);
					return;
				}
			}
			catch (Exception e) {
				log.debug("Failed to dismiss cookie banner with selector {}: {}", selector, e.getMessage());
			}
		}
		log.debug("No cookie banner found to dismiss");
	}

	private void attachNetworkDiagnostics(Page page, String phase) {
		if (!networkDiagnosticsEnabled) {
			return;
		}

		page.onRequest(request -> log.debug("[{}][REQ] {} {}", phase, request.method(), request.url()));
		page.onResponse(response -> {
			String server = response.headerValue("server");
			String cfRay = response.headerValue("cf-ray");
			boolean hasSetCookie = response.headerValue("set-cookie") != null;
			log.debug("[{}][RES] {} {} (server={}, cf-ray={}, set-cookie={})", phase, response.status(), response.url(),
					server, cfRay, hasSetCookie);
		});
	}

	private void validateCredentials() {
		if (properties.zalando().email() == null || properties.zalando().email().isBlank()) {
			throw new LoginFailedException("Missing ZALANDO_EMAIL");
		}
		if (properties.zalando().password() == null || properties.zalando().password().isBlank()) {
			throw new LoginFailedException("Missing ZALANDO_PASSWORD");
		}
	}

	private boolean isLoginUrl(String url) {
		return url != null && url.contains("/login");
	}

	private boolean isAccountsDomain(String url) {
		return url != null && url.contains("accounts.zalando.com");
	}

	private boolean isAuthenticatedDestination(String url) {
		return url != null && url.contains("zalando-lounge.ch") && !isLoginUrl(url) && !isAccountsDomain(url);
	}

	private void openLoginFormIfNeeded(Page page) {
		try {
			var loginEntry = page.locator(LOGIN_ENTRY_SELECTOR).first();
			if (loginEntry.isVisible()) {
				log.debug("Opening login form via {}", LOGIN_ENTRY_SELECTOR);
				loginEntry.click(new com.microsoft.playwright.Locator.ClickOptions().setTimeout(elementTimeoutMs));
				page.waitForLoadState(LoadState.DOMCONTENTLOADED);
			}
		}
		catch (Exception e) {
			log.debug("Login entry button not used or not visible: {}", e.getMessage());
		}
	}

	private void waitForPasswordStepReady(Page page) {
		page.waitForFunction(PASSWORD_STEP_READY_JS, null,
				new com.microsoft.playwright.Page.WaitForFunctionOptions().setTimeout(loginPostSubmitTimeoutMs));
	}

	private double configuredTimeout(long configuredValue, long defaultValue) {
		if (configuredValue > 0) {
			return configuredValue;
		}
		return defaultValue;
	}

	private String joinUrl(String baseUrl, String path) {
		if (baseUrl.endsWith("/")) {
			return baseUrl + path;
		}
		return baseUrl + "/" + path;
	}

	private void saveSessionAtomically(BrowserContext context) {
		try {
			// Snapshotting storage state requires a live remote context. When the
			// Patchright connection dropped, context.storageState() fails deep in the
			// driver with an opaque NoSuchElementException; bail out early with a clear
			// message instead so the log names the real problem (dead browser endpoint).
			var browser = context.browser();
			if (browser != null && !browser.isConnected()) {
				log.error(
						"Cannot save session state: Patchright browser at {} is disconnected — there is no live context to snapshot",
						properties.zalando().browserWsEndpoint());
				return;
			}

			Path sessionPath = Path.of(properties.zalando().sessionFile());
			Path sessionDir = sessionPath.getParent();
			if (sessionDir != null) {
				Files.createDirectories(sessionDir);
			}

			Path tempPath = sessionPath.resolveSibling(sessionPath.getFileName() + ".tmp");
			Path backupPath = sessionPath.resolveSibling(sessionPath.getFileName() + ".bak");
			context.storageState(new BrowserContext.StorageStateOptions().setPath(tempPath));

			if (Files.exists(sessionPath)) {
				Files.copy(sessionPath, backupPath, StandardCopyOption.REPLACE_EXISTING);
			}

			moveAtomically(tempPath, sessionPath);
		}
		catch (Exception e) {
			if (isBrowserUnavailable(e)) {
				log.error("Could not save session state: Patchright browser/context unavailable at {} [{}: {}]",
						properties.zalando().browserWsEndpoint(), e.getClass().getSimpleName(), e.getMessage());
			}
			else {
				log.error("Could not save session state: {}", e.getMessage(), e);
			}
		}
	}

	private void moveAtomically(Path source, Path target) throws java.io.IOException {
		try {
			Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		}
		catch (AtomicMoveNotSupportedException ignored) {
			Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private void typeHumanLike(Page page, String selector, String value) {
		var field = page.locator(selector).first();
		moveMouseTo(page, field);
		field.click(new com.microsoft.playwright.Locator.ClickOptions().setTimeout(elementTimeoutMs));
		field.fill("");
		field.pressSequentially(value,
				new com.microsoft.playwright.Locator.PressSequentiallyOptions()
					.setDelay(ThreadLocalRandom.current().nextDouble(60, 140))
					.setTimeout(elementTimeoutMs));
	}

	private void clickHumanLike(Page page, String selector) {
		var target = page.locator(selector).first();
		moveMouseTo(page, target);
		target.click(new com.microsoft.playwright.Locator.ClickOptions().setTimeout(elementTimeoutMs));
	}

	/**
	 * Browses the public site like a human before touching the login form. Akamai's bot
	 * manager only mints a trusted token after its sensor JS observes normal interaction,
	 * so we load the homepage, accept cookies, dwell, scroll and move the mouse first.
	 * The resulting cookies are persisted to the session file so later runs (and
	 * restarts) start already "warm".
	 */
	private void warmUpSession(BrowserContext context) {
		Page page = null;
		try {
			page = context.newPage();
			attachNetworkDiagnostics(page, "warm-up");
			log.debug("Warming up Akamai session via homepage {}", homeUrl);
			page.navigate(homeUrl,
					new com.microsoft.playwright.Page.NavigateOptions().setWaitUntil(WaitUntilState.COMMIT)
						.setTimeout(loginNavigationTimeoutMs));
			page.waitForLoadState(LoadState.DOMCONTENTLOADED);
			humanPause();
			acceptCookieBannerIfPresent(page);
			wanderMouse(page);
			gentleScroll(page);
			humanPause();
			saveSessionAtomically(context);
			log.debug("Warm-up complete; Akamai cookies persisted");
		}
		catch (Exception e) {
			log.debug("Warm-up navigation failed (continuing to login): {}", e.getMessage());
		}
		finally {
			if (page != null) {
				try {
					page.close();
				}
				catch (Exception ignored) {
				}
			}
		}
	}

	/**
	 * Lets the accounts.zalando.com Akamai sensor observe genuine interaction before the
	 * email is submitted. That domain runs its own bot manager, independent of the lounge
	 * homepage warm-up, and rejects the verify-email call with a generic "something went
	 * wrong" error when the sensor has not yet posted valid telemetry. Waiting for the
	 * network to settle, moving the mouse, scrolling and dwelling gives the sensor the
	 * interaction window a real user would produce.
	 */
	private void warmUpAuthPageSensor(Page page) {
		try {
			page.waitForLoadState(LoadState.NETWORKIDLE,
					new com.microsoft.playwright.Page.WaitForLoadStateOptions().setTimeout(elementTimeoutMs));
		}
		catch (Exception ignored) {
			// Best-effort: a busy long-poll connection can keep the network from going
			// fully idle; proceed with the interaction warm-up regardless.
		}
		wanderMouse(page);
		gentleScroll(page);
		try {
			Thread.sleep(ThreadLocalRandom.current().nextLong(2200, 4200));
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		wanderMouse(page);
	}

	/**
	 * Moves the cursor to a random point inside the target element along a multi-step
	 * path, so a real pointer trajectory precedes the click. Best-effort: any failure
	 * (e.g. element without a bounding box) is ignored and the click proceeds.
	 */
	private void moveMouseTo(Page page, com.microsoft.playwright.Locator locator) {
		try {
			var box = locator.boundingBox();
			if (box == null) {
				return;
			}
			var rnd = ThreadLocalRandom.current();
			double x = box.x + box.width * rnd.nextDouble(0.3, 0.7);
			double y = box.y + box.height * rnd.nextDouble(0.3, 0.7);
			page.mouse().move(x, y, new com.microsoft.playwright.Mouse.MoveOptions().setSteps(rnd.nextInt(10, 25)));
			Thread.sleep(rnd.nextLong(80, 220));
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		catch (Exception ignored) {
		}
	}

	private void wanderMouse(Page page) {
		try {
			var rnd = ThreadLocalRandom.current();
			int moves = rnd.nextInt(3, 6);
			for (int i = 0; i < moves; i++) {
				double x = rnd.nextDouble(100, 1800);
				double y = rnd.nextDouble(100, 900);
				page.mouse().move(x, y, new com.microsoft.playwright.Mouse.MoveOptions().setSteps(rnd.nextInt(8, 20)));
				Thread.sleep(rnd.nextLong(120, 380));
			}
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		catch (Exception ignored) {
		}
	}

	private void gentleScroll(Page page) {
		try {
			var rnd = ThreadLocalRandom.current();
			int scrolls = rnd.nextInt(2, 4);
			for (int i = 0; i < scrolls; i++) {
				page.mouse().wheel(0, rnd.nextDouble(200, 600));
				Thread.sleep(rnd.nextLong(200, 500));
			}
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		catch (Exception ignored) {
		}
	}

	/**
	 * Types into the password field by focusing it via the DOM and emitting real
	 * keystrokes. Zalando wraps the field in an aria-disabled section, which makes
	 * Playwright's actionability refuse a normal locator interaction even though the
	 * native input is enabled; focusing + keyboard events bypasses that while still
	 * producing trusted input events.
	 */
	private void typePasswordHumanLike(Page page, String value) {
		page.evaluate("() => document.querySelector('#password').focus()");
		page.keyboard()
			.type(value, new com.microsoft.playwright.Keyboard.TypeOptions()
				.setDelay(ThreadLocalRandom.current().nextDouble(60, 140)));
	}

	private void humanPause() {
		try {
			Thread.sleep(ThreadLocalRandom.current().nextLong(450, 1100));
		}
		catch (InterruptedException interruptedException) {
			Thread.currentThread().interrupt();
		}
	}

	private void sleepWithJitter(int attempt) {
		long exponential = authRetryBaseDelayMs * (1L << Math.min(6, Math.max(0, attempt - 1)));
		long jitter = ThreadLocalRandom.current().nextLong(0, Math.max(1L, authRetryBaseDelayMs));
		long totalDelayMs = Math.min(15_000L, exponential + jitter);
		try {
			Thread.sleep(totalDelayMs);
		}
		catch (InterruptedException interruptedException) {
			Thread.currentThread().interrupt();
		}
	}

	private Path captureDiagnostics(BrowserContext context, Page page, String phase, Exception exception) {
		if (!networkDiagnosticsEnabled || exception == null) {
			return null;
		}

		String timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
			.withZone(ZoneOffset.UTC)
			.format(Instant.now());
		Path dir = diagnosticsDir.resolve(timestamp + "-" + sanitize(phase));

		try {
			Files.createDirectories(dir);
			String currentUrl = "n/a";
			String title = "n/a";
			String html = null;

			if (page != null) {
				try {
					currentUrl = String.valueOf(page.url());
				}
				catch (Exception ignored) {
				}
				try {
					title = String.valueOf(page.title());
				}
				catch (Exception ignored) {
				}
				try {
					page.screenshot(new Page.ScreenshotOptions().setPath(dir.resolve("failure.png")).setFullPage(true));
				}
				catch (PlaywrightException ignored) {
				}
				try {
					html = page.content();
				}
				catch (Exception ignored) {
				}
			}

			// Record the concrete exception type and the browser/WS connection state so a
			// dead Patchright connection (no page ever created — NoSuchElementException
			// from
			// the driver, url/title = n/a, browserConnected=false) is unambiguously
			// distinguishable from a changed Zalando login selector (page present, real
			// URL,
			// timeout waiting for an element). The previous "category=UNKNOWN,
			// message=null"
			// told the operator nothing.
			Throwable rootCause = rootCause(exception);
			String details = "phase=" + phase + "\n" + "category=" + classifyFailure(exception) + "\n"
					+ "exceptionClass=" + exception.getClass().getName() + "\n" + "message=" + exception.getMessage()
					+ "\n" + "rootCauseClass=" + rootCause.getClass().getName() + "\n" + "rootCauseMessage="
					+ rootCause.getMessage() + "\n" + "browserConnected=" + browserConnectionState(context) + "\n"
					+ "wsEndpoint=" + properties.zalando().browserWsEndpoint() + "\n" + "url=" + currentUrl + "\n"
					+ "title=" + title + "\n";
			Files.writeString(dir.resolve("failure.txt"), details, StandardCharsets.UTF_8);
			if (html != null && !html.isBlank()) {
				Files.writeString(dir.resolve("failure.html"), html, StandardCharsets.UTF_8);
			}
			return dir;
		}
		catch (Exception diagnosticsError) {
			log.debug("Could not capture diagnostics for phase {}: {}", phase, diagnosticsError.getMessage());
			return null;
		}
	}

	private String browserConnectionState(BrowserContext context) {
		if (context == null) {
			return "unknown";
		}
		try {
			var browser = context.browser();
			if (browser == null) {
				return "unknown";
			}
			return String.valueOf(browser.isConnected());
		}
		catch (Exception e) {
			return "unknown";
		}
	}

	private static Throwable rootCause(Throwable throwable) {
		Throwable current = throwable;
		while (current.getCause() != null && current.getCause() != current) {
			current = current.getCause();
		}
		return current;
	}

	private String sanitize(String value) {
		if (value == null || value.isBlank()) {
			return "unknown";
		}
		return value.replaceAll("[^a-zA-Z0-9._-]", "-");
	}

	private AuthFailureCategory classifyFailure(Throwable error) {
		if (error == null) {
			return AuthFailureCategory.UNKNOWN;
		}
		if (isBrowserUnavailable(error)) {
			return AuthFailureCategory.BROWSER_UNAVAILABLE;
		}
		String message = String.valueOf(error.getMessage()).toLowerCase(Locale.ROOT);
		if (message.contains("timeout") || message.contains("timed out")) {
			return AuthFailureCategory.TIMEOUT;
		}
		if (message.contains("http2") || message.contains("protocol") || message.contains("econn")
				|| message.contains("connection") || message.contains("net::")) {
			return AuthFailureCategory.NETWORK;
		}
		if (message.contains("captcha") || message.contains("challenge") || message.contains("akamai")
				|| message.contains("access denied") || message.contains("forbidden") || message.contains("waf")) {
			return AuthFailureCategory.CHALLENGE;
		}
		if (message.contains("invalid") || message.contains("unauthorized") || message.contains("still on login")
				|| message.contains("missing zalando_")) {
			return AuthFailureCategory.AUTH_INVALID;
		}
		return AuthFailureCategory.UNKNOWN;
	}

	/**
	 * Detects the "browser/context was never established" failure mode. When the
	 * Patchright server never accepted the connection (or silently dropped the remote
	 * browser), the Playwright Java driver fails to resolve the expected object and
	 * throws a bare {@link java.util.NoSuchElementException} with a {@code null} message
	 * — which previously surfaced as the useless {@code [UNKNOWN]: null}. Also matches
	 * the driver's "target/browser/context has been closed" and websocket-connection
	 * messages, walking the whole cause chain.
	 */
	private boolean isBrowserUnavailable(Throwable error) {
		for (Throwable current = error; current != null; current = current.getCause()) {
			if (current instanceof java.util.NoSuchElementException) {
				return true;
			}
			String message = current.getMessage() == null ? "" : current.getMessage().toLowerCase(Locale.ROOT);
			if (message.contains("target page, context or browser has been closed")
					|| message.contains("browser has been closed") || message.contains("context has been closed")
					|| message.contains("connection closed") || message.contains("websocket")
					|| message.contains("no usable page") || message.contains("patchright browser")) {
				return true;
			}
			if (current.getCause() == current) {
				break;
			}
		}
		return false;
	}

	private void clearCachedSession() {
		try {
			var sessionPath = Path.of(properties.zalando().sessionFile());
			if (Files.exists(sessionPath)) {
				Files.delete(sessionPath);
				log.info("Cleared invalid cached session file for next retry");
			}
		}
		catch (Exception e) {
			log.warn("Could not clear cached session: {}", e.getMessage());
		}
	}

	public static class LoginFailedException extends RuntimeException {

		private final AuthFailureCategory category;

		private final Path diagnosticsPath;

		public LoginFailedException(String message) {
			super(message);
			this.category = AuthFailureCategory.UNKNOWN;
			this.diagnosticsPath = null;
		}

		public LoginFailedException(String message, AuthFailureCategory category, Path diagnosticsPath) {
			super(message);
			this.category = category == null ? AuthFailureCategory.UNKNOWN : category;
			this.diagnosticsPath = diagnosticsPath;
		}

		public AuthFailureCategory category() {
			return category;
		}

		public Path diagnosticsPath() {
			return diagnosticsPath;
		}

	}

	public enum AuthFailureCategory {

		AUTH_INVALID,

		BROWSER_UNAVAILABLE,

		CHALLENGE,

		NETWORK,

		TIMEOUT,

		UNKNOWN

	}

}
