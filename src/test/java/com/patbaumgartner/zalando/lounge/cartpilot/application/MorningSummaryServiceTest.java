package com.patbaumgartner.zalando.lounge.cartpilot.application;

import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.Decision;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.ReservationStatus;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.port.out.DiscoveredProductPort;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.port.out.NotificationPort;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.port.out.ProductReservationPort;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.port.out.ProfilePort;
import com.patbaumgartner.zalando.lounge.cartpilot.testdata.ProductTestData;
import com.patbaumgartner.zalando.lounge.cartpilot.testdata.ProfileTestData;
import com.patbaumgartner.zalando.lounge.cartpilot.testdata.ReservationTestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("MorningSummaryService")
class MorningSummaryServiceTest {

	@Mock
	private ProductReservationPort reservationPort;

	@Mock
	private DiscoveredProductPort productPort;

	@Mock
	private ProfilePort profilePort;

	@Mock
	private NotificationPort notification;

	private MorningSummaryService service;

	@BeforeEach
	void setUp() {
		service = new MorningSummaryService(reservationPort, productPort, profilePort, notification);
	}

	@Nested
	@DisplayName("Summary with no items")
	class EmptySummary {

		@Test
		@DisplayName("sends summary with zero counts when no reservations exist")
		void sendsEmptySummary() {
			when(reservationPort.findByStatus(ReservationStatus.BLOCKED)).thenReturn(List.of());
			when(reservationPort.findByStatus(ReservationStatus.IN_CART)).thenReturn(List.of());
			when(reservationPort.findByStatus(ReservationStatus.PENDING)).thenReturn(List.of());
			when(profilePort.findAll()).thenReturn(List.of());
			when(productPort.findByDiscoveredAt(any(LocalDate.class))).thenReturn(List.of());

			service.sendSummary();

			var captor = ArgumentCaptor.forClass(NotificationPort.MorningSummary.class);
			verify(notification).sendMorningSummary(captor.capture());

			var summary = captor.getValue();
			assertThat(summary.autoReserved()).isEmpty();
			assertThat(summary.notifyOnly()).isEmpty();
			assertThat(summary.campaignCount()).isZero();
		}

	}

	@Nested
	@DisplayName("Summary with items")
	class SummaryWithItems {

		@Test
		@DisplayName("includes IN_CART reservations as autoReserved")
		void includesInCartReservations() {
			var profile = ProfileTestData.aProfile().withId(1L).build();
			var product = ProductTestData.mammutJacket();
			var inCart = ReservationTestData.inCartReservation();

			when(reservationPort.findByStatus(ReservationStatus.BLOCKED)).thenReturn(List.of());
			when(reservationPort.findByStatus(ReservationStatus.IN_CART)).thenReturn(List.of(inCart));
			when(reservationPort.findByStatus(ReservationStatus.PENDING)).thenReturn(List.of());
			when(profilePort.findAll()).thenReturn(List.of(profile));
			when(productPort.findByDiscoveredAt(any(LocalDate.class))).thenReturn(List.of(product));

			service.sendSummary();

			var captor = ArgumentCaptor.forClass(NotificationPort.MorningSummary.class);
			verify(notification).sendMorningSummary(captor.capture());

			var summary = captor.getValue();
			assertThat(summary.autoReserved()).hasSize(1);
		}

		@Test
		@DisplayName("includes BLOCKED reservations so bot-walled items stay reachable")
		void includesBlockedReservations() {
			var profile = ProfileTestData.aProfile().withId(1L).build();
			var product = ProductTestData.mammutJacket();
			var blocked = ReservationTestData.aReservation()
				.withProductId(product.id())
				.withProfileId(profile.id())
				.withStatus(ReservationStatus.BLOCKED)
				.build();

			when(reservationPort.findByStatus(ReservationStatus.IN_CART)).thenReturn(List.of());
			when(reservationPort.findByStatus(ReservationStatus.BLOCKED)).thenReturn(List.of(blocked));
			when(reservationPort.findByStatus(ReservationStatus.PENDING)).thenReturn(List.of());
			when(profilePort.findAll()).thenReturn(List.of(profile));
			when(productPort.findByDiscoveredAt(any(LocalDate.class))).thenReturn(List.of(product));

			service.sendSummary();

			var captor = ArgumentCaptor.forClass(NotificationPort.MorningSummary.class);
			verify(notification).sendMorningSummary(captor.capture());

			assertThat(captor.getValue().blocked()).hasSize(1);
			assertThat(captor.getValue().autoReserved()).isEmpty();
		}

		@Test
		@DisplayName("counts unique campaign IDs for campaign count")
		void countsUniqueCampaigns() {
			var product1 = ProductTestData.aProduct().withId(1L).withCampaignId("camp-A").build();
			var product2 = ProductTestData.aProduct().withId(2L).withCampaignId("camp-A").build();
			var product3 = ProductTestData.aProduct().withId(3L).withCampaignId("camp-B").build();

			when(reservationPort.findByStatus(ReservationStatus.BLOCKED)).thenReturn(List.of());
			when(reservationPort.findByStatus(ReservationStatus.IN_CART)).thenReturn(List.of());
			when(reservationPort.findByStatus(ReservationStatus.PENDING)).thenReturn(List.of());
			when(profilePort.findAll()).thenReturn(List.of());
			when(productPort.findByDiscoveredAt(any(LocalDate.class)))
				.thenReturn(List.of(product1, product2, product3));

			service.sendSummary();

			var captor = ArgumentCaptor.forClass(NotificationPort.MorningSummary.class);
			verify(notification).sendMorningSummary(captor.capture());

			assertThat(captor.getValue().campaignCount()).isEqualTo(2);
		}

		@Test
		@DisplayName("filters PENDING reservations to NOTIFY_ONLY decisions")
		void filtersNotifyOnlyDecisions() {
			var profile = ProfileTestData.aProfile().withId(1L).build();
			var product = ProductTestData.jackWolfskinFleece();
			var notifyReservation = ReservationTestData.aReservation()
				.withProductId(product.id())
				.withProfileId(profile.id())
				.withDecision(Decision.NOTIFY_ONLY)
				.withStatus(ReservationStatus.PENDING)
				.build();

			when(reservationPort.findByStatus(ReservationStatus.BLOCKED)).thenReturn(List.of());
			when(reservationPort.findByStatus(ReservationStatus.IN_CART)).thenReturn(List.of());
			when(reservationPort.findByStatus(ReservationStatus.PENDING)).thenReturn(List.of(notifyReservation));
			when(profilePort.findAll()).thenReturn(List.of(profile));
			when(productPort.findByDiscoveredAt(any(LocalDate.class))).thenReturn(List.of(product));

			service.sendSummary();

			var captor = ArgumentCaptor.forClass(NotificationPort.MorningSummary.class);
			verify(notification).sendMorningSummary(captor.capture());

			assertThat(captor.getValue().notifyOnly()).hasSize(1);
		}

	}

}
