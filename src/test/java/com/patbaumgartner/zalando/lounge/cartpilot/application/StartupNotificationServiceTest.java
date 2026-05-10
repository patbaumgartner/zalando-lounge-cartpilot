package com.patbaumgartner.zalando.lounge.cartpilot.application;

import com.patbaumgartner.zalando.lounge.cartpilot.domain.port.out.NotificationPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("StartupNotificationService")
class StartupNotificationServiceTest {

	@Mock
	private NotificationPort notification;

	@Mock
	private Environment environment;

	private StartupNotificationService service;

	@BeforeEach
	void setUp() {
		service = new StartupNotificationService(notification, environment);
	}

	@Test
	@DisplayName("sends startup message with active profile names")
	void sendsStartupMessageWithProfiles() {
		when(environment.getActiveProfiles()).thenReturn(new String[] { "dev" });

		service.notifyOnStartup();

		verify(notification).sendGroupMessage(contains("CartPilot started"));
		verify(notification).sendGroupMessage(contains("dev"));
	}

}
