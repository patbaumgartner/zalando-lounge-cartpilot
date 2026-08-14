package com.patbaumgartner.zalando.lounge.cartpilot.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("PlaywrightConfiguration")
class PlaywrightConfigurationTest {

	@Test
	@DisplayName("rejects a missing browser endpoint with an actionable message")
	void rejectsBlankEndpoint() {
		assertThatThrownBy(() -> new PlaywrightConfiguration().playwright(propertiesWithEndpoint("  ")))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("browser-ws-endpoint")
			.hasMessageContaining("docker compose up patchright");
	}

	@Test
	@DisplayName("defines no Browser bean, so an unreachable sidecar cannot stop the application from starting")
	void definesNoBrowserBean() {
		assertThat(Arrays.stream(PlaywrightConfiguration.class.getDeclaredMethods()).map(method -> method.getName()))
			.doesNotContain("browser");
	}

	private static CartPilotProperties propertiesWithEndpoint(String endpoint) {
		var zalando = mock(CartPilotProperties.ZalandoProperties.class);
		when(zalando.browserWsEndpoint()).thenReturn(endpoint);
		var properties = mock(CartPilotProperties.class);
		when(properties.zalando()).thenReturn(zalando);
		return properties;
	}

}
