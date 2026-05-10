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

	private static final String EMAIL_SELECTOR = "input[type='email'], input[name='email']";

	private static final String PASSWORD_SELECTOR = "input[type='password']";

	private static final String SUBMIT_SELECTOR = "button[type='submit'], button:has-text('Login'), button:has-text('Anmelden')";

	private static final List<String> COOKIE_ACCEPT_SELECTORS = List.of("#onetrust-accept-btn-handler",
			"button[data-testid='uc-accept-all-button']", "button:has-text('Accept all')",
			"button:has-text('Allow all')", "button:has-text('Akzeptieren')", "button:has-text('Alle akzeptieren')");

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
				|| category == AuthFailureCategory.CHALLENGE;
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

		return isSessionValidByProbe(context);
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
			return !isLoginUrl(page.url());
		}
		catch (Exception e) {
			log.info("Session validation stage B probe failed: {}", e.getMessage());
			return false;
		}
	}

	private void login(BrowserContext context) {
		validateCredentials();
		log.info("Logging in to Zalando Lounge...");
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

				page.waitForSelector(EMAIL_SELECTOR,
						new com.microsoft.playwright.Page.WaitForSelectorOptions().setTimeout(elementTimeoutMs));

				page.fill(EMAIL_SELECTOR, properties.zalando().email());
				page.fill(PASSWORD_SELECTOR, properties.zalando().password());

				page.click(SUBMIT_SELECTOR,
						new com.microsoft.playwright.Page.ClickOptions().setTimeout(elementTimeoutMs));

				page.waitForLoadState(LoadState.DOMCONTENTLOADED);
				page.waitForURL(url -> !isLoginUrl(url),
						new com.microsoft.playwright.Page.WaitForURLOptions().setTimeout(loginPostSubmitTimeoutMs));

				if (isLoginUrl(page.url())) {
					throw new LoginFailedException("Login failed - still on login page");
				}

				saveSessionAtomically(context);
				log.info("Login successful on attempt {}/{}, session saved", attempt, loginMaxAttempts);
				return;
			}
			catch (Exception e) {
				lastError = e;
				lastCategory = classifyFailure(e);
				var diagnosticPath = captureDiagnostics(page, "auto-login-" + attempt, e);
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

		clearCachedSession();
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

			page.waitForURL(url -> !isLoginUrl(url),
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
					captureDiagnostics(page, "manual-login-failed", e));
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

	private void acceptCookieBannerIfPresent(Page page) {
		for (String selector : COOKIE_ACCEPT_SELECTORS) {
			try {
				var button = page.locator(selector).first();
				if (button.isVisible()) {
					button.click(new com.microsoft.playwright.Locator.ClickOptions().setTimeout(3000));
					log.debug("Accepted cookie banner with selector: {}", selector);
					return;
				}
			}
			catch (Exception ignored) {
				// Try next selector.
			}
		}
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
			log.error("Could not save session state: {}", e.getMessage(), e);
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

	private Path captureDiagnostics(Page page, String phase, Exception exception) {
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
			}

			String details = "phase=" + phase + "\n" + "category=" + classifyFailure(exception) + "\n" + "message="
					+ exception.getMessage() + "\n" + "url=" + currentUrl + "\n" + "title=" + title + "\n";
			Files.writeString(dir.resolve("failure.txt"), details, StandardCharsets.UTF_8);
			return dir;
		}
		catch (Exception diagnosticsError) {
			log.debug("Could not capture diagnostics for phase {}: {}", phase, diagnosticsError.getMessage());
			return null;
		}
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

		CHALLENGE,

		NETWORK,

		TIMEOUT,

		UNKNOWN

	}

}
