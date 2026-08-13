package com.patbaumgartner.zalando.lounge.cartpilot.application;

import com.patbaumgartner.zalando.lounge.cartpilot.config.CartPilotProperties;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.CartAddResult;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.ReservationStatus;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.port.out.BrowserPort;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.port.out.DiscoveredProductPort;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.port.out.NotificationPort;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.port.out.ProductReservationPort;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.port.out.ProfilePort;
import com.patbaumgartner.zalando.lounge.cartpilot.testdata.ProductTestData;
import com.patbaumgartner.zalando.lounge.cartpilot.testdata.ProfileTestData;
import com.patbaumgartner.zalando.lounge.cartpilot.testdata.ReservationTestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("CartKeepAliveService")
class CartKeepAliveServiceTest {

	@Mock
	private ProductReservationPort reservationPort;

	@Mock
	private DiscoveredProductPort productPort;

	@Mock
	private ProfilePort profilePort;

	@Mock
	private BrowserPort browser;

	@Mock
	private NotificationPort notification;

	private CartKeepAliveService keepAliveService;

	@BeforeEach
	void setUp() {
		var props = buildProperties(2, 20);
		keepAliveService = new CartKeepAliveService(reservationPort, productPort, profilePort, browser, notification,
				props, new BrowserGate());
	}

	@Test
	@DisplayName("does nothing when cart is empty")
	void doesNothingWhenCartIsEmpty() {
		when(reservationPort.findByStatus(ReservationStatus.IN_CART)).thenReturn(List.of());

		keepAliveService.keepAlive();

		verifyNoInteractions(browser, productPort);
	}

	@Test
	@DisplayName("removes and re-adds the item to prolong the hold when still available")
	void refreshesHoldWhenItemStillAvailable() {
		var reservation = ReservationTestData.inCartReservation();
		var product = ProductTestData.mammutJacket();

		when(reservationPort.findByStatus(ReservationStatus.IN_CART)).thenReturn(List.of(reservation));
		when(productPort.findById(reservation.productId())).thenReturn(Optional.of(product));
		when(browser.refreshCartItem(product.productUrl(), reservation.size())).thenReturn(CartAddResult.added());

		keepAliveService.keepAlive();

		assertThat(reservation.status()).isEqualTo(ReservationStatus.IN_CART);
		verify(browser).refreshCartItem(product.productUrl(), reservation.size());
		verify(browser, never()).isItemInCart(anyString());
		verify(reservationPort).update(reservation);
		verifyNoInteractions(notification);
	}

	@Test
	@DisplayName("marks EXPIRED and posts a link list when the item can no longer be re-added")
	void expiresWhenItemCannotBeRefreshed() {
		var reservation = ReservationTestData.inCartReservation();
		var product = ProductTestData.mammutJacket();
		var profile = ProfileTestData.aProfile().withId(reservation.profileId()).build();

		when(reservationPort.findByStatus(ReservationStatus.IN_CART)).thenReturn(List.of(reservation));
		when(productPort.findById(reservation.productId())).thenReturn(Optional.of(product));
		when(profilePort.findAll()).thenReturn(List.of(profile));
		when(browser.refreshCartItem(eq(product.productUrl()), any()))
			.thenReturn(CartAddResult.sizeUnavailable("size 52 not purchasable"));

		keepAliveService.keepAlive();

		assertThat(reservation.status()).isEqualTo(ReservationStatus.EXPIRED);
		verify(reservationPort).update(reservation);

		var captor = ArgumentCaptor.forClass(List.class);
		verify(notification).sendProductLinks(contains("ran out"), captor.capture());
		assertThat(captor.getValue()).hasSize(1);
	}

	@Test
	@DisplayName("keeps the reservation IN_CART and reports it when bot protection blocks the refresh")
	void keepsReservationWhenRefreshIsBlocked() {
		var reservation = ReservationTestData.inCartReservation();
		var product = ProductTestData.mammutJacket();
		var profile = ProfileTestData.aProfile().withId(reservation.profileId()).build();

		when(reservationPort.findByStatus(ReservationStatus.IN_CART)).thenReturn(List.of(reservation));
		when(productPort.findById(reservation.productId())).thenReturn(Optional.of(product));
		when(profilePort.findAll()).thenReturn(List.of(profile));
		when(browser.refreshCartItem(eq(product.productUrl()), any()))
			.thenReturn(CartAddResult.blocked(403, "bot protection refused the basket call"));

		keepAliveService.keepAlive();

		assertThat(reservation.status()).isEqualTo(ReservationStatus.IN_CART);
		verify(reservationPort, never()).update(reservation);
		verify(notification).sendProductLinks(contains("blocked"), anyList());
	}

	// ── Helpers ────────────────────────────────────────────────

	private CartPilotProperties buildProperties(int maxHours, int expiryMinutes) {
		var cart = mock(CartPilotProperties.CartProperties.class);
		when(cart.maxKeepAliveHours()).thenReturn(maxHours);
		when(cart.expiryMinutes()).thenReturn(expiryMinutes);

		var props = mock(CartPilotProperties.class);
		when(props.cart()).thenReturn(cart);
		return props;
	}

}
