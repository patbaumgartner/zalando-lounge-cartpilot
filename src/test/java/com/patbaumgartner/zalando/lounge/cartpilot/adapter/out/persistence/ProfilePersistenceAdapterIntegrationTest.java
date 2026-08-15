package com.patbaumgartner.zalando.lounge.cartpilot.adapter.out.persistence;

import com.patbaumgartner.zalando.lounge.cartpilot.PostgresContainerSupport;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.BrandTier;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.Category;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.NONE;

@SpringBootTest(webEnvironment = NONE)
@ActiveProfiles("test")
@DisplayName("ProfilePersistenceAdapter (integration)")
class ProfilePersistenceAdapterIntegrationTest extends PostgresContainerSupport {

	@Autowired
	private ProfilePort adapter;

	@Autowired
	private JdbcClient jdbcClient;

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

		@Test
		@DisplayName("finds a profile by id")
		void findsById() {
			var pat = adapter.findByName("Pat").orElseThrow();

			assertThat(adapter.findById(pat.id())).contains(pat);
		}

		@Test
		@DisplayName("returns empty for an unknown or null id")
		void returnsEmptyForUnknownId() {
			assertThat(adapter.findById(-1L)).isEmpty();
			assertThat(adapter.findById(null)).isEmpty();
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

		@Test
		@DisplayName("keeps the original creation timestamp across edits")
		void preservesCreatedAtOnUpdate() {
			var pat = adapter.findByName("Pat").orElseThrow();
			var createdAt = createdAtOf(pat.id());

			adapter.save(pat.withSize(Category.SHOES, "44"));

			assertThat(createdAtOf(pat.id())).isEqualTo(createdAt);
			assertThat(adapter.findById(pat.id()).orElseThrow().sizeFor(Category.SHOES)).contains("44");
		}

		@Test
		@DisplayName("round-trips sizes, brand tiers and aliases")
		void roundTripsProfileFields() {
			var pat = adapter.findByName("Pat").orElseThrow();

			var saved = adapter.save(pat.withBrandInTier(BrandTier.TIER_2, "Ortovox"));

			assertThat(saved.brandTier2()).contains("Ortovox");
			assertThat(adapter.findByName("Pat").orElseThrow().brandTier2()).contains("Ortovox");
			assertThat(saved.brandAliases()).containsEntry("TNF", "The North Face");
			assertThat(saved.sizes()).isNotEmpty();
		}

		private LocalDateTime createdAtOf(Long profileId) {
			return jdbcClient.sql("SELECT created_at FROM profiles WHERE id = :id")
				.param("id", profileId)
				.query(LocalDateTime.class)
				.single();
		}

	}

}
