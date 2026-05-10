package com.patbaumgartner.zalando.lounge.cartpilot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

@ConfigurationProperties(prefix = "cartpilot")
public record CartPilotProperties(ZalandoProperties zalando, TelegramProperties telegram, CartProperties cart,
		String brandAliases, SchedulerProperties scheduler) {

	public record ZalandoProperties(String email, String password, String sessionFile, String baseUrl,
			String campaignUrl, int retryIntervalSeconds, int retryMaxAttempts, long navigationTimeoutMs,
			long elementTimeoutMs, int loginMaxAttempts, boolean headless, boolean headedLoginFallbackEnabled,
			long headedLoginTimeoutMs, boolean networkDiagnosticsEnabled, long sessionCheckTimeoutMs,
			long loginNavigationTimeoutMs, long loginPostSubmitTimeoutMs, long authRetryBaseDelayMs,
			int authContextResetRetries, boolean trustSessionFileInDev, String diagnosticsDir) {
	}

	public record TelegramProperties(String botToken, String groupChatId, String webhookUrl) {
	}

	public record CartProperties(int expiryMinutes, int keepAliveIntervalMinutes, int maxKeepAliveHours) {
	}

	public record SchedulerProperties(String scanCron, String summaryCron, String keepAliveCron, String timezone) {
	}

	/** Parses {@code KEY=Value,KEY2=Value2} into a map. */
	public Map<String, String> parsedBrandAliases() {
		if (brandAliases == null || brandAliases.isBlank()) {
			return Map.of();
		}
		var result = new HashMap<String, String>();
		for (String entry : brandAliases.split(",")) {
			var parts = entry.split("=", 2);
			if (parts.length == 2) {
				result.put(parts[0].trim(), parts[1].trim());
			}
		}
		return result;
	}
}
