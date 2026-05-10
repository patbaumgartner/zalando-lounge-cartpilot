package com.patbaumgartner.zalando.lounge.cartpilot.config;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Playwright;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
public class PlaywrightConfiguration {

	private static final Logger log = LoggerFactory.getLogger(PlaywrightConfiguration.class);

	@Bean
	@Profile("!test")
	public Playwright playwright() {
		log.info("Initialising Playwright");
		return Playwright.create();
	}

	@Bean
	@Profile("!test")
	public Browser browser(Playwright playwright, CartPilotProperties properties) {
		// Patchright (undetected Chromium) has no Java binding, so it runs as a
		// separate
		// browser server (the "patchright" docker-compose service). The Java client
		// connects to it over the Playwright wire protocol.
		String ws = properties.zalando().browserWsEndpoint();
		if (ws == null || ws.isBlank()) {
			throw new IllegalStateException("cartpilot.zalando.browser-ws-endpoint must point to the Patchright server "
					+ "(e.g. ws://patchright:3000/cartpilot). Start it via 'docker compose up patchright'.");
		}
		log.info("Connecting to Patchright browser server at {}", ws);
		return playwright.chromium().connect(ws);
	}

}
