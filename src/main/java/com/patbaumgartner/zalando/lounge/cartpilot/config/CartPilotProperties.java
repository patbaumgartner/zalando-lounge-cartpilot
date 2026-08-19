package com.patbaumgartner.zalando.lounge.cartpilot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "cartpilot")
public record CartPilotProperties(ZalandoProperties zalando, TelegramProperties telegram, CartProperties cart,
		SchedulerProperties scheduler) {

	public record ZalandoProperties(String email, String password, String sessionFile, String baseUrl,
			String campaignUrl, int retryIntervalSeconds, int retryMaxAttempts, long navigationTimeoutMs,
			long elementTimeoutMs, int loginMaxAttempts, boolean headless, boolean headedLoginFallbackEnabled,
			long headedLoginTimeoutMs, boolean networkDiagnosticsEnabled, long sessionCheckTimeoutMs,
			long loginNavigationTimeoutMs, long loginPostSubmitTimeoutMs, long authRetryBaseDelayMs,
			int authContextResetRetries, boolean trustSessionFileInDev, String diagnosticsDir,
			String browserWsEndpoint) {

		/** Absolute basket/cart URL. */
		public String cartUrl() {
			return ZalandoUrls.cartUrl(baseUrl);
		}

		/** Absolute cart API endpoint returning the basket contents as JSON. */
		public String cartApiUrl() {
			return ZalandoUrls.cartApiUrl(baseUrl);
		}

		/** Resolves a possibly-relative product URL against {@link #baseUrl()}. */
		public String resolveUrl(String url) {
			return ZalandoUrls.resolveUrl(baseUrl, url);
		}
	}

	public record TelegramProperties(String botToken, String groupChatId) {
	}

	/**
	 * @param blockCooldownMs pause after a bot-wall rejection before the scan attempts
	 * the next basket call
	 */
	public record CartProperties(int expiryMinutes, int keepAliveIntervalMinutes, int maxKeepAliveHours,
			long blockCooldownMs) {
	}

	public record SchedulerProperties(String scanCron, String summaryCron, String keepAliveCron, String timezone) {
	}
}
