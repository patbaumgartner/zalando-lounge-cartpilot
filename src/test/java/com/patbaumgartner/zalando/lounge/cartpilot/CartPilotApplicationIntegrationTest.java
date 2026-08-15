package com.patbaumgartner.zalando.lounge.cartpilot;

import com.patbaumgartner.zalando.lounge.cartpilot.domain.port.out.BrowserPort;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.port.out.NotificationPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.NONE;

/**
 * Smoke test: verifies that the full Spring application context loads against a real
 * PostgreSQL (Testcontainers) with Playwright + Telegram mocked out.
 */
@SpringBootTest(webEnvironment = NONE)
@ActiveProfiles("test")
@DisplayName("CartPilot application context (integration)")
class CartPilotApplicationIntegrationTest extends PostgresContainerSupport {

	/**
	 * Replace Playwright and Telegram adapters so no real browser or bot starts.
	 */
	@MockitoBean
	private BrowserPort browserPort;

	@MockitoBean
	private NotificationPort notificationPort;

	@MockitoBean
	private TelegramClient telegramClient;

	@Test
	@DisplayName("Spring context loads and sends startup message")
	void contextLoads() {
		// If we reach here the context started successfully — Flyway ran migrations,
		// Spring Data JDBC repositories are bound, all services are wired up.
		assertThat(true).isTrue();
		verify(notificationPort, atLeastOnce()).sendGroupMessage(contains("CartPilot started"));
	}

}
