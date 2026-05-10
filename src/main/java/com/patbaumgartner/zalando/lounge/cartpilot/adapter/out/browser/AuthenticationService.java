package com.patbaumgartner.zalando.lounge.cartpilot.adapter.out.browser;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.options.LoadState;
import com.patbaumgartner.zalando.lounge.cartpilot.config.CartPilotProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Manages Zalando Lounge session state via Playwright's storage state API. Credentials
 * are read exclusively from environment variables — never hardcoded.
 */
@Service
@Profile("!test")
public class AuthenticationService {

	private static final Logger log = LoggerFactory.getLogger(AuthenticationService.class);

	private static final String LOGIN_URL = "https://www.zalando-lounge.ch/login";

	private static final String EMAIL_SELECTOR = "input[type='email'], input[name='email']";

	private static final String PASSWORD_SELECTOR = "input[type='password']";

	private static final String SUBMIT_SELECTOR = "button[type='submit']";

	private static final String SESSION_CHECK_URL = "https://www.zalando-lounge.ch/event";

	private final CartPilotProperties properties;

	public AuthenticationService(CartPilotProperties properties) {
		this.properties = properties;
	}

	public void ensureAuthenticated(BrowserContext context) {
		if (hasValidSession()) {
			try {
				loadSession(context);
				if (isSessionValid(context)) {
					log.debug("Session loaded from file and is valid");
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

	private boolean hasValidSession() {
		Path sessionPath = Path.of(properties.zalando().sessionFile());
		return Files.exists(sessionPath) && sessionPath.toFile().length() > 0;
	}

	private void loadSession(BrowserContext context) {
		try {
			var sessionPath = Path.of(properties.zalando().sessionFile());
			context.addCookies(List.of()); // clear first
			context.storageState(new BrowserContext.StorageStateOptions().setPath(sessionPath));
		}
		catch (Exception e) {
			throw new RuntimeException("Could not load session state", e);
		}
	}

	private boolean isSessionValid(BrowserContext context) {
		try (var page = context.newPage()) {
			page.navigate(SESSION_CHECK_URL);
			page.waitForLoadState(LoadState.NETWORKIDLE);
			// If redirected to login, session is invalid
			return !page.url().contains("/login");
		}
	}

	private void login(BrowserContext context) {
		log.info("Logging in to Zalando Lounge...");
		try (var page = context.newPage()) {
			page.navigate(LOGIN_URL);
			page.waitForLoadState(LoadState.NETWORKIDLE);

			page.fill(EMAIL_SELECTOR, properties.zalando().email());
			page.fill(PASSWORD_SELECTOR, properties.zalando().password());
			page.click(SUBMIT_SELECTOR);
			page.waitForLoadState(LoadState.NETWORKIDLE);

			if (page.url().contains("/login")) {
				throw new LoginFailedException("Login failed — still on login page");
			}

			saveSession(context);
			log.info("Login successful, session saved");
		}
	}

	private void saveSession(BrowserContext context) {
		try {
			var sessionPath = Path.of(properties.zalando().sessionFile());
			Files.createDirectories(sessionPath.getParent());
			context.storageState(new BrowserContext.StorageStateOptions().setPath(sessionPath));
		}
		catch (Exception e) {
			log.error("Could not save session state: {}", e.getMessage(), e);
		}
	}

	public static class LoginFailedException extends RuntimeException {

		public LoginFailedException(String message) {
			super(message);
		}

	}

}
