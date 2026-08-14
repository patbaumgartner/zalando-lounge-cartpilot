package com.patbaumgartner.zalando.lounge.cartpilot.adapter.out.browser;

import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.Campaign;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.Gender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@DisplayName("CampaignScraper")
class CampaignScraperTest {

	private static final Campaign CAMPAIGN = new Campaign("camp-1", "Winter Sale", LocalDate.now(),
			"https://www.zalando-lounge.ch/campaigns/camp-1");

	private final ObjectMapper objectMapper = new ObjectMapper();

	private CampaignScraper scraper(FakePageHttpClient http) {
		return new CampaignScraper(objectMapper, mock(AuthenticationService.class), http);
	}

	private static String article(String sku, String brand, String name, Integer price, Integer specialPrice) {
		return """
				{"brand":"%s","nameCategoryTag":"%s","gender":["male"],
				 "simples":[{"sku":"%s-52","stockStatus":"AVAILABLE","filterValue":"52"}],
				 %s %s
				 "urlPath":{"40":"/campaigns/camp-1/articles/%s"}}""".formatted(brand, name, sku,
				price == null ? "" : "\"price\":" + price + ",",
				specialPrice == null ? "" : "\"specialPrice\":" + specialPrice + ",", sku);
	}

	@Nested
	@DisplayName("scrapeProducts")
	class ScrapeProducts {

		@Test
		@DisplayName("maps a catalog article into a discovered product")
		void mapsArticle() {
			var http = new FakePageHttpClient()
				.enqueueJson(200, "[" + article("SKU1", "Mammut", "Convey Jacke", 40000, 18000) + "]")
				.fallingBackTo(new InPageResponse(200, "[]", ""));

			var products = scraper(http).scrapeProducts(null, CAMPAIGN);

			assertThat(products).hasSize(1);
			var product = products.getFirst();
			assertThat(product.brand()).isEqualTo("Mammut");
			assertThat(product.originalPrice()).isEqualByComparingTo(new BigDecimal("400.00"));
			assertThat(product.loungePrice()).isEqualByComparingTo(new BigDecimal("180.00"));
			assertThat(product.discountPct()).isEqualTo(55);
			assertThat(product.gender()).isEqualTo(Gender.MEN);
			assertThat(product.sizesAvailable()).containsExactly("52");
			assertThat(product.productUrl()).isEqualTo("https://www.zalando-lounge.ch/campaigns/camp-1/articles/SKU1");
		}

		@Test
		@DisplayName("drops articles with no usable lounge price instead of pricing them at CHF 0")
		void dropsArticlesWithoutPrice() {
			var http = new FakePageHttpClient()
				.enqueueJson(200,
						"[" + article("SKU1", "Mammut", "No Price", 40000, null) + ","
								+ article("SKU2", "Mammut", "Zero Price", 40000, 0) + ","
								+ article("SKU3", "Mammut", "Priced", 40000, 18000) + "]")
				.fallingBackTo(new InPageResponse(200, "[]", ""));

			var products = scraper(http).scrapeProducts(null, CAMPAIGN);

			assertThat(products).hasSize(1);
			assertThat(products.getFirst().name()).isEqualTo("Priced");
		}

		@Test
		@DisplayName("returns nothing when the articles endpoint is refused")
		void returnsNothingOnBotWall() {
			var http = new FakePageHttpClient().enqueueJson(403, "Access Denied");

			assertThat(scraper(http).scrapeProducts(null, CAMPAIGN)).isEmpty();
		}

		@Test
		@DisplayName("retries a rate-limited article page before giving up")
		void retriesRateLimitedPage() {
			var http = new FakePageHttpClient().enqueueJson(429, "Too Many Requests")
				.enqueueJson(200, "[" + article("SKU1", "Mammut", "Convey Jacke", 40000, 18000) + "]")
				.fallingBackTo(new InPageResponse(200, "[]", ""));

			assertThat(scraper(http).scrapeProducts(null, CAMPAIGN)).hasSize(1);
		}

	}

	@Nested
	@DisplayName("parseOpenCampaigns")
	class OpenCampaigns {

		private static final String FAR_FUTURE = "2999-01-01T00:00:00Z";

		private static final String LONG_PAST = "2000-01-01T00:00:00Z";

		private com.microsoft.playwright.Page page() {
			return mock(com.microsoft.playwright.Page.class);
		}

		@Test
		@DisplayName("keeps campaigns that are open right now")
		void keepsOpenCampaigns() {
			var http = new FakePageHttpClient().enqueueJson(200,
					"""
							{"open_campaigns":[{"campaign_id":"c1","name":"Open","starts_at":"%s","ends_at":"%s","url":"/campaigns/c1"}]}"""
						.formatted(LONG_PAST, FAR_FUTURE));

			var campaigns = scraper(http).scrapeOpenCampaigns(page(), "https://www.zalando-lounge.ch/event");

			assertThat(campaigns).hasSize(1);
			assertThat(campaigns.getFirst().campaignId()).isEqualTo("c1");
		}

		@Test
		@DisplayName("drops campaigns that already ended or have not started")
		void dropsClosedAndFutureCampaigns() {
			var http = new FakePageHttpClient().enqueueJson(200, """
					{"open_campaigns":[
					 {"campaign_id":"ended","starts_at":"%s","ends_at":"%s"},
					 {"campaign_id":"future","starts_at":"%s","ends_at":"%s"}]}""".formatted(LONG_PAST, LONG_PAST,
					FAR_FUTURE, FAR_FUTURE));

			assertThat(scraper(http).scrapeOpenCampaigns(page(), "https://www.zalando-lounge.ch/event")).isEmpty();
		}

		@Test
		@DisplayName("returns nothing when the campaigns endpoint is refused")
		void returnsNothingWhenRefused() {
			var http = new FakePageHttpClient().enqueueJson(403, "Access Denied");

			assertThat(scraper(http).scrapeOpenCampaigns(page(), "https://www.zalando-lounge.ch/event")).isEmpty();
		}

		@Test
		@DisplayName("returns nothing for a logged-out HTML response")
		void returnsNothingForHtml() {
			var http = new FakePageHttpClient().enqueueJson(200, "<html>login</html>");

			assertThat(scraper(http).scrapeOpenCampaigns(page(), "https://www.zalando-lounge.ch/event")).isEmpty();
		}

	}

}
