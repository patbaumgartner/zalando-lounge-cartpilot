package com.patbaumgartner.zalando.lounge.cartpilot.adapter.out.persistence;

import com.patbaumgartner.zalando.lounge.cartpilot.PostgresContainerSupport;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.port.out.BrowserPort;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.port.out.NotificationPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.time.LocalDateTime;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.NONE;

/**
 * Verifies that the Flyway migrations actually produced the indexes and constraints the
 * application depends on, rather than only that they ran without error.
 */
@SpringBootTest(webEnvironment = NONE)
@ActiveProfiles("test")
@DisplayName("Schema migrations (integration)")
class SchemaMigrationIntegrationTest extends PostgresContainerSupport {

	@Autowired
	private JdbcClient jdbcClient;

	@MockitoBean
	private BrowserPort browserPort;

	@MockitoBean
	private NotificationPort notificationPort;

	@MockitoBean
	private TelegramClient telegramClient;

	@Test
	@DisplayName("V3 created every index the hot queries rely on")
	void createsPerformanceIndexes() {
		assertThat(indexNames()).contains("idx_product_reservations_status", "idx_product_reservations_created_at",
				"idx_discovered_products_discovered_at", "idx_purchased_items_profile_id",
				"uq_purchased_items_profile_product");
	}

	@Test
	@DisplayName("a profile cannot record the same purchase twice")
	void rejectsDuplicatePurchase() {
		long profileId = jdbcClient.sql("SELECT id FROM profiles WHERE name = 'Pat'").query(Long.class).single();
		long productId = insertProduct();

		insertPurchase(profileId, productId);

		assertThatThrownBy(() -> insertPurchase(profileId, productId))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	private long insertProduct() {
		jdbcClient.sql("""
				INSERT INTO discovered_products
				  (campaign_id, brand, name, category, gender, sizes_available,
				   original_price, lounge_price, discount_pct, product_url, status, discovered_at)
				VALUES ('camp-dup', 'Mammut', 'Dup Test', 'JACKETS', 'MEN', '52',
				        310.00, 189.00, 39, 'https://example.test/campaigns/camp-dup/articles/DUP-1', 'DISCOVERED', ?)
				""").param(LocalDateTime.now()).update();
		return jdbcClient.sql("SELECT id FROM discovered_products WHERE campaign_id = 'camp-dup'")
			.query(Long.class)
			.single();
	}

	private void insertPurchase(long profileId, long productId) {
		jdbcClient.sql("INSERT INTO purchased_items (profile_id, product_id, purchased_by_username) VALUES (?, ?, ?)")
			.params(profileId, productId, "pat")
			.update();
	}

	private java.util.List<String> indexNames() {
		// information_schema has no view for indexes; PostgreSQL's own catalog does.
		return jdbcClient.sql("SELECT indexname FROM pg_indexes WHERE schemaname = 'public'")
			.query(String.class)
			.list()
			.stream()
			.map(name -> name.toLowerCase(Locale.ROOT))
			.toList();
	}

}
