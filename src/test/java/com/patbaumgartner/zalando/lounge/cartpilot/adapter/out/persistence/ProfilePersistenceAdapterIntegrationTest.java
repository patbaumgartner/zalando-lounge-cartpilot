package com.patbaumgartner.zalando.lounge.cartpilot.adapter.out.persistence;

import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.Gender;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.Profile;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.port.out.BrowserPort;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.port.out.NotificationPort;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.port.out.ProfilePort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.NONE;

@SpringBootTest(webEnvironment = NONE)
@ActiveProfiles("test")
@DisplayName("ProfilePersistenceAdapter (integration)")
class ProfilePersistenceAdapterIntegrationTest {

	@Autowired
	private ProfilePort adapter;

	@MockitoBean
	private BrowserPort browserPort;

	@MockitoBean
	private NotificationPort notificationPort;

	@MockitoBean
	private TelegramClient telegramClient;

	@Nested
	@DisplayName("Query operations")
	class Query {

		@Test
		@DisplayName("returns all seeded profiles")
		void returnsSeededProfiles() {
			var profiles = adapter.findAll();
			assertThat(profiles).isNotEmpty();
		}

		@Test
		@DisplayName("finds Pat by name from seed data")
		void findsPatByName() {
			var result = adapter.findByName("Pat");
			assertThat(result).isPresent();
			assertThat(result.get().gender()).isEqualTo(Gender.MEN);
			assertThat(result.get().active()).isTrue();
		}

	}

	@Nested
	@DisplayName("CRUD operations")
	class Crud {

		@Test
		@DisplayName("returns profiles from persistence")
		void returnsProfilesFromPersistence() {
			var profiles = adapter.findAll();
			assertThat(profiles).isNotEmpty();
		}

		@Test
		@DisplayName("finds active profiles")
		void findsActiveProfiles() {
			var active = adapter.findAllActive();
			assertThat(active).allMatch(Profile::active);
		}

	}

}
