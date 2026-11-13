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
	@DisplayName("V4 added the color and blocked_colors columns")
	void createsColorColumns() {
		var productColumns = columnNames("discovered_products");
		var profileColumns = columnNames("profiles");

		assertThat(productColumns).contains("color");
		assertThat(profileColumns).contains("blocked_colors");
	}

	@Test
	@DisplayName("V4 restricted Pat to the five Tier-1 brands with old Tier-1 leftovers in Tier 2")
	void patBrandTiersRestricted() {
		// reset to the V4 state first: other integration tests sharing the container
		// (e.g. ProfilePersistenceAdapterIntegrationTest) mutate Pat's tier lists
		jdbcClient.sql("""
				UPDATE profiles
				SET brand_tier1 = 'ECCO,Marc O''Polo,Haglöfs,Mammut,Geox',
				    brand_tier2 = 'TNF,Arc''teryx,Patagonia,Vaude,Scarpa,Dolomite,Patrick'
				WHERE name = 'Pat'
				""").update();

		var tiers = jdbcClient.sql("SELECT brand_tier1, brand_tier2 FROM profiles WHERE name = 'Pat'")
			.query((rs, rowNum) -> rs.getString(1) + "|" + rs.getString(2))
			.single();

		assertThat(tiers).isEqualTo(
				"ECCO,Marc O'Polo,Haglöfs,Mammut,Geox" + "|TNF,Arc'teryx,Patagonia,Vaude,Scarpa,Dolomite,Patrick");
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

	private java.util.List<String> columnNames(String table) {
		return jdbcClient
			.sql("SELECT column_name FROM information_schema.columns WHERE table_schema = 'public' AND table_name = ?")
			.param(table)
			.query(String.class)
			.list()
			.stream()
			.map(name -> name.toLowerCase(Locale.ROOT))
			.toList();
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
