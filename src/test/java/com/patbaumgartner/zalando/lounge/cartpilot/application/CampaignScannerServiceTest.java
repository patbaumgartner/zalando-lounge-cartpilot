package com.patbaumgartner.zalando.lounge.cartpilot.application;

import com.patbaumgartner.zalando.lounge.cartpilot.config.CartPilotProperties;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.Campaign;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.CartAddResult;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.Decision;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.FilterResult;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.port.out.BrowserPort;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.port.out.DiscoveredProductPort;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.port.out.KnownBrandPort;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.port.out.NotificationPort;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.port.out.ProfilePort;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.port.out.PurchasedItemPort;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.service.ProductFilter;
import com.patbaumgartner.zalando.lounge.cartpilot.testdata.ProductTestData;
import com.patbaumgartner.zalando.lounge.cartpilot.testdata.ProfileTestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("CampaignScannerService")
class CampaignScannerServiceTest {

	@Mock
	private BrowserPort browser;

	@Mock
	private ProfilePort profilePort;

	@Mock
	private DiscoveredProductPort discoveredProductPort;

	@Mock
	private KnownBrandPort knownBrandPort;

	@Mock
	private PurchasedItemPort purchasedItemPort;

	@Mock
	private ProductFilter productFilter;

	@Mock
	private CartService cartService;

	@Mock
	private NotificationPort notification;

	@Mock
	private CartPilotProperties properties;

	@Mock
	private CartPilotProperties.ZalandoProperties zalandoProps;

	private CampaignScannerService service;

	@BeforeEach
	void setUp() {
		when(properties.zalando()).thenReturn(zalandoProps);
		when(zalandoProps.retryMaxAttempts()).thenReturn(1);
		when(zalandoProps.retryIntervalSeconds()).thenReturn(1);

		service = new CampaignScannerService(browser, profilePort, discoveredProductPort, knownBrandPort,
				purchasedItemPort, productFilter, cartService, notification, properties, new BrowserGate());
	}

	@Nested
	@DisplayName("Authentication failures")
	class AuthenticationFailures {

		@Test
		@DisplayName("sends group message and aborts when authentication fails")
		void abortsAndNotifiesOnAuthFailure() {
			doThrow(new BrowserPort.BrowserException("Login failed", new RuntimeException("auth error"))).when(browser)
				.ensureAuthenticated();

			service.scan();

			verify(notification).sendGroupMessage(contains("Login failed"));
			verify(browser, never()).fetchTodayCampaigns();
		}

	}

	@Nested
	@DisplayName("No campaigns today")
	class NoCampaigns {

		@Test
		@DisplayName("sends info message when no campaigns are found")
		void notifiesWhenNoCampaigns() {
			when(browser.fetchTodayCampaigns()).thenReturn(List.of());

			service.scan();

			verify(notification).sendGroupMessage(contains("No new campaigns"));
			verify(discoveredProductPort, never()).saveAll(anyList());
		}

	}

	@Nested
	@DisplayName("Successful scan")
	class SuccessfulScan {

		@Test
		@DisplayName("persists products and updates brand catalogue on successful scan")
		void persistsProductsAndUpdatesBrands() {
			var campaign = new Campaign("camp-001", "Winter Sale", LocalDate.now(), "https://example.com/c1");
			var product = ProductTestData.mammutJacket();
			var profile = ProfileTestData.aProfile().withId(1L).build();

			when(browser.fetchTodayCampaigns()).thenReturn(List.of(campaign));
			when(browser.scrapeProducts(campaign)).thenReturn(List.of(product));
			when(discoveredProductPort.saveAll(anyList())).thenReturn(List.of(product));
			when(profilePort.findAllActive()).thenReturn(List.of(profile));
			when(purchasedItemPort.findProductIdsByProfileId(1L)).thenReturn(Set.of());
			when(productFilter.filter(anyList(), any(), any())).thenReturn(List.of());

			service.scan();

			verify(discoveredProductPort, atLeastOnce()).saveAll(anyList());
			verify(knownBrandPort).upsertAll(anyList());
		}

		@Test
		@DisplayName("dispatches AUTO_RESERVE results to CartService")
		void dispatchesAutoReserveToCartService() {
			var campaign = new Campaign("camp-001", "Summer Sale", LocalDate.now(), "https://example.com/c1");
			var product = ProductTestData.mammutJacket();
			var profile = ProfileTestData.aProfile().withId(1L).build();
			var autoReserveResult = new FilterResult(product, profile, "52", Decision.AUTO_RESERVE, null, 90);

			when(browser.fetchTodayCampaigns()).thenReturn(List.of(campaign));
			when(browser.scrapeProducts(campaign)).thenReturn(List.of(product));
			when(discoveredProductPort.saveAll(anyList())).thenReturn(List.of(product));
			when(profilePort.findAllActive()).thenReturn(List.of(profile));
			when(purchasedItemPort.findProductIdsByProfileId(1L)).thenReturn(Set.of());
			when(productFilter.filter(anyList(), any(), any())).thenReturn(List.of(autoReserveResult));
			when(cartService.addToCart(autoReserveResult)).thenReturn(CartAddResult.added());

			service.scan();

			verify(cartService).addToCart(autoReserveResult);
			verify(notification).sendProductLinks(contains("Reserved"), anyList());
		}

		@Test
		@DisplayName("posts a manual-grab link list when bot protection blocks the cart add")
		void postsLinkListWhenBlocked() {
			var campaign = new Campaign("camp-001", "Summer Sale", LocalDate.now(), "https://example.com/c1");
			var product = ProductTestData.mammutJacket();
			var profile = ProfileTestData.aProfile().withId(1L).build();
			var autoReserveResult = new FilterResult(product, profile, "52", Decision.AUTO_RESERVE, null, 90);

			when(browser.fetchTodayCampaigns()).thenReturn(List.of(campaign));
			when(browser.scrapeProducts(campaign)).thenReturn(List.of(product));
			when(discoveredProductPort.saveAll(anyList())).thenReturn(List.of(product));
			when(profilePort.findAllActive()).thenReturn(List.of(profile));
			when(purchasedItemPort.findProductIdsByProfileId(1L)).thenReturn(Set.of());
			when(productFilter.filter(anyList(), any(), any())).thenReturn(List.of(autoReserveResult));
			when(cartService.addToCart(autoReserveResult))
				.thenReturn(CartAddResult.blocked(403, "bot protection refused the basket call"));

			service.scan();

			var captor = ArgumentCaptor.forClass(List.class);
			verify(notification).sendProductLinks(contains("Blocked"), captor.capture());
			assertThat(captor.getValue()).hasSize(1);

			var reportCaptor = ArgumentCaptor.forClass(NotificationPort.ScanReport.class);
			verify(notification).sendScanReport(reportCaptor.capture());
			assertThat(reportCaptor.getValue().blockedCount()).isEqualTo(1);
			assertThat(reportCaptor.getValue().reservedCount()).isZero();
		}

		@Test
		@DisplayName("sends NOTIFY_ONLY results to reservation queue and posts their links")
		void queuesNotifyOnlyResults() {
			var campaign = new Campaign("camp-001", "Spring Sale", LocalDate.now(), "https://example.com/c1");
			var product = ProductTestData.jackWolfskinFleece();
			var profile = ProfileTestData.aProfile().withId(1L).build();
			var notifyResult = new FilterResult(product, profile, "M", Decision.NOTIFY_ONLY, null, 60);

			when(browser.fetchTodayCampaigns()).thenReturn(List.of(campaign));
			when(browser.scrapeProducts(campaign)).thenReturn(List.of(product));
			when(discoveredProductPort.saveAll(anyList())).thenReturn(List.of(product));
			when(profilePort.findAllActive()).thenReturn(List.of(profile));
			when(purchasedItemPort.findProductIdsByProfileId(1L)).thenReturn(Set.of());
			when(productFilter.filter(anyList(), any(), any())).thenReturn(List.of(notifyResult));

			service.scan();

			verify(cartService).reserveForNotification(notifyResult);

			var captor = ArgumentCaptor.forClass(List.class);
			verify(notification).sendProductLinks(contains("notify only"), captor.capture());
			assertThat(captor.getValue()).hasSize(1);
		}

		@Test
		@DisplayName("always posts a scan report, even when nothing matched")
		void alwaysPostsScanReport() {
			var campaign = new Campaign("camp-001", "Winter Sale", LocalDate.now(), "https://example.com/c1");
			var product = ProductTestData.mammutJacket();
			var profile = ProfileTestData.aProfile().withId(1L).build();

			when(browser.fetchTodayCampaigns()).thenReturn(List.of(campaign));
			when(browser.scrapeProducts(campaign)).thenReturn(List.of(product));
			when(discoveredProductPort.saveAll(anyList())).thenReturn(List.of(product));
			when(profilePort.findAllActive()).thenReturn(List.of(profile));
			when(purchasedItemPort.findProductIdsByProfileId(1L)).thenReturn(Set.of());
			when(productFilter.filter(anyList(), any(), any())).thenReturn(List.of());

			service.scan();

			var captor = ArgumentCaptor.forClass(NotificationPort.ScanReport.class);
			verify(notification).sendScanReport(captor.capture());
			assertThat(captor.getValue().campaignCount()).isEqualTo(1);
			assertThat(captor.getValue().productCount()).isEqualTo(1);
			assertThat(captor.getValue().matchCount()).isZero();
			verify(notification, never()).sendProductLinks(anyString(), anyList());
		}

	}

	@Nested
	@DisplayName("Retry logic")
	class RetryLogic {

		@Test
		@DisplayName("retries fetching campaigns up to the configured max attempts")
		void retriesUpToMaxAttempts() {
			when(zalandoProps.retryMaxAttempts()).thenReturn(3);
			when(browser.fetchTodayCampaigns()).thenReturn(List.of());

			service.scan();

			verify(browser, times(3)).fetchTodayCampaigns();
			verify(notification).sendGroupMessage(contains("No new campaigns"));
		}

	}

}
