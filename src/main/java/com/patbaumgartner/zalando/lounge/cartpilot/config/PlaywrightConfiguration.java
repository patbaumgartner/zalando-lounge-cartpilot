package com.patbaumgartner.zalando.lounge.cartpilot.config;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.util.List;

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
		boolean headless = properties.zalando().headless();
		log.info("Launching Chromium (headless={})", headless);
		return playwright.chromium()
			.launch(new BrowserType.LaunchOptions().setHeadless(headless)
				.setArgs(List.of("--no-sandbox", "--disable-setuid-sandbox", "--disable-dev-shm-usage",
						"--disable-http2")));
	}

}
