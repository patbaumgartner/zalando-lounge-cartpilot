package com.patbaumgartner.zalando.lounge.cartpilot.application;

import com.patbaumgartner.zalando.lounge.cartpilot.config.CartPilotProperties;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.BrandTier;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.CartAddOutcome;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.CartAddResult;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.CartClearResult;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.Decision;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.DiscoveredProduct;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.FilterResult;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.ProductReservation;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.Profile;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.ReservationStatus;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.port.out.BrowserPort;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.port.out.DiscoveredProductPort;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.port.out.NotificationPort;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.port.out.ProductReservationPort;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.port.out.ProfilePort;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.port.out.PurchasedItemPort;
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
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("CartService")
class CartServiceTest {

	@Mock
	private BrowserPort browser;

	@Mock
	private ProductReservationPort reservationPort;

	@Mock
	private DiscoveredProductPort productPort;

	@Mock
	private ProfilePort profilePort;

	@Mock
	private PurchasedItemPort purchasedItemPort;

	@Mock
	private NotificationPort notification;

	private CartService cartService;

	@BeforeEach
	void setUp() {
		var properties = mockProperties(20);
		cartService = new CartService(browser, reservationPort, productPort, profilePort, purchasedItemPort,
				notification, properties, new BrowserGate());
	}

	@Nested
	@DisplayName("addToCart")
	class AddToCart {

		@Test
		@DisplayName("saves IN_CART reservation and sends notification when item added successfully")
		void successfullyAddsToCart() {
			// Given
			var product = ProductTestData.mammutJacket();
			var profile = ProfileTestData.pat();
			var result = autoReserveResult(product, profile, "52", 70);
			var saved = ReservationTestData.inCartReservation();

			when(browser.addToCart(anyString(), anyString())).thenReturn(CartAddResult.added());
			when(reservationPort.save(any())).thenReturn(saved);
			when(notification.sendReservationNotification(any(), any(), any())).thenReturn(99);

			// When
			var outcome = cartService.addToCart(result);

			// Then
			assertThat(outcome.outcome()).isEqualTo(CartAddOutcome.ADDED);

			var reservationCaptor = ArgumentCaptor.forClass(ProductReservation.class);
			verify(reservationPort).save(reservationCaptor.capture());
			assertThat(reservationCaptor.getValue().status()).isEqualTo(ReservationStatus.IN_CART);
			assertThat(reservationCaptor.getValue().size()).isEqualTo("52");

			verify(notification).sendReservationNotification(saved, profile, product);
			verify(reservationPort).update(saved);
			assertThat(saved.telegramMsgId()).isEqualTo(99);
		}

		@Test
		@DisplayName("saves OUT_OF_STOCK reservation when the size is no longer purchasable")
		void savesOutOfStockWhenSizeGone() {
			// Given
			var product = ProductTestData.mammutJacket();
			var profile = ProfileTestData.pat();
			var result = autoReserveResult(product, profile, "52", 70);

			when(browser.addToCart(anyString(), anyString()))
				.thenReturn(CartAddResult.sizeUnavailable("size 52 not purchasable"));
			when(reservationPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

			// When
			var outcome = cartService.addToCart(result);

			// Then
			assertThat(outcome.outcome()).isEqualTo(CartAddOutcome.SIZE_UNAVAILABLE);

			var captor = ArgumentCaptor.forClass(ProductReservation.class);
			verify(reservationPort).save(captor.capture());
			assertThat(captor.getValue().status()).isEqualTo(ReservationStatus.OUT_OF_STOCK);

			verifyNoInteractions(notification);
		}

		@Test
		@DisplayName("saves FAILED, not OUT_OF_STOCK, when the basket call fails for an unrelated reason")
		void savesFailedWhenBasketCallFails() {
			var product = ProductTestData.mammutJacket();
			var profile = ProfileTestData.pat();
			var result = autoReserveResult(product, profile, "52", 70);

			when(browser.addToCart(anyString(), anyString()))
				.thenReturn(CartAddResult.failed(500, "basket call answered HTTP 500"));
			when(reservationPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

			var outcome = cartService.addToCart(result);

			assertThat(outcome.outcome()).isEqualTo(CartAddOutcome.FAILED);

			var captor = ArgumentCaptor.forClass(ProductReservation.class);
			verify(reservationPort).save(captor.capture());
			assertThat(captor.getValue().status()).isEqualTo(ReservationStatus.FAILED);
		}

		@Test
		@DisplayName("saves BLOCKED reservation when bot protection refuses the basket call")
		void savesBlockedWhenBotProtectionRefuses() {
			// Given
			var product = ProductTestData.mammutJacket();
			var profile = ProfileTestData.pat();
			var result = autoReserveResult(product, profile, "52", 70);

			when(browser.addToCart(anyString(), anyString()))
				.thenReturn(CartAddResult.blocked(403, "bot protection refused the basket call"));
			when(reservationPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

			// When
			var outcome = cartService.addToCart(result);

			// Then
			assertThat(outcome.outcome()).isEqualTo(CartAddOutcome.BLOCKED);
			assertThat(outcome.httpStatus()).isEqualTo(403);

			var captor = ArgumentCaptor.forClass(ProductReservation.class);
			verify(reservationPort).save(captor.capture());
			assertThat(captor.getValue().status()).isEqualTo(ReservationStatus.BLOCKED);

			verifyNoInteractions(notification);
		}

	}

	@Nested
	@DisplayName("reserveForNotification")
	class ReserveForNotification {

		@Test
		@DisplayName("saves PENDING reservation without browser interaction")
		void savesPendingReservation() {
			// Given
			var product = ProductTestData.jackWolfskinFleece();
			var profile = ProfileTestData.pat();
			var result = notifyOnlyResult(product, profile, "52", 30);
			when(reservationPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

			// When
			cartService.reserveForNotification(result);

			// Then
			var captor = ArgumentCaptor.forClass(ProductReservation.class);
			verify(reservationPort).save(captor.capture());
			assertThat(captor.getValue().status()).isEqualTo(ReservationStatus.PENDING);
			assertThat(captor.getValue().decision()).isEqualTo(Decision.NOTIFY_ONLY);

			verifyNoInteractions(browser);
		}

	}

	@Nested
	@DisplayName("handleBuy")
	class HandleBuy {

		@Test
		@DisplayName("marks purchase initiated and notifies Telegram group")
		void handlesBuyCallback() {
			// Given
			var reservation = ReservationTestData.inCartReservation();
			var product = ProductTestData.mammutJacket();
			var profile = ProfileTestData.pat();

			when(reservationPort.findById(1L)).thenReturn(Optional.of(reservation));
			when(productPort.findById(product.id())).thenReturn(Optional.of(product));
			when(profilePort.findById(profile.id())).thenReturn(Optional.of(profile));

			// When
			cartService.handleBuy(1L, "pat");

			// Then
			assertThat(reservation.status()).isEqualTo(ReservationStatus.PURCHASE_INITIATED);
			verify(reservationPort).update(reservation);
			verify(purchasedItemPort).save(any());
			verify(notification).updateGroupMessage(anyInt(), anyString());
			verify(notification).sendGroupMessage(anyString());
		}

		@Test
		@DisplayName("throws when reservation not found")
		void throwsWhenReservationNotFound() {
			when(reservationPort.findById(99L)).thenReturn(Optional.empty());

			assertThatThrownBy(() -> cartService.handleBuy(99L, "pat")).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("99");
		}

		@Test
		@DisplayName("does not book a second purchase when Buy is tapped again")
		void ignoresRepeatedBuy() {
			var reservation = ReservationTestData.inCartReservation();
			reservation.markPurchaseInitiated();
			var product = ProductTestData.mammutJacket();

			when(reservationPort.findById(1L)).thenReturn(Optional.of(reservation));
			when(productPort.findById(product.id())).thenReturn(Optional.of(product));

			cartService.handleBuy(1L, "pat");

			verifyNoInteractions(purchasedItemPort);
			verify(reservationPort, never()).update(any(ProductReservation.class));
		}

	}

	@Nested
	@DisplayName("handleSkip")
	class HandleSkip {

		@Test
		@DisplayName("removes from cart, marks REJECTED and notifies Telegram")
		void handlesSkipCallback() {
			// Given
			var reservation = ReservationTestData.inCartReservation();
			var product = ProductTestData.mammutJacket();
			var profile = ProfileTestData.pat();

			when(reservationPort.findById(1L)).thenReturn(Optional.of(reservation));
			when(productPort.findById(product.id())).thenReturn(Optional.of(product));
			when(profilePort.findById(profile.id())).thenReturn(Optional.of(profile));

			// When
			cartService.handleSkip(1L, "pat");

			// Then
			assertThat(reservation.status()).isEqualTo(ReservationStatus.REJECTED);
			verify(browser).removeFromCart(product.productUrl());
			verify(reservationPort).update(reservation);
			verify(notification).updateGroupMessage(anyInt(), anyString());
		}

		@Test
		@DisplayName("ignores Skip once the item has been bought")
		void ignoresSkipAfterPurchase() {
			var reservation = ReservationTestData.inCartReservation();
			reservation.markPurchaseInitiated();
			var product = ProductTestData.mammutJacket();

			when(reservationPort.findById(1L)).thenReturn(Optional.of(reservation));
			when(productPort.findById(product.id())).thenReturn(Optional.of(product));

			cartService.handleSkip(1L, "pat");

			assertThat(reservation.status()).isEqualTo(ReservationStatus.PURCHASE_INITIATED);
			verify(browser, never()).removeFromCart(anyString());
			verify(reservationPort, never()).update(any(ProductReservation.class));
		}

	}

	@Nested
	@DisplayName("clearCart")
	class ClearCart {

		@Test
		@DisplayName("clears browser cart and marks all IN_CART reservations as REJECTED")
		void clearsCartAndUpdatesReservations() {
			var reservation1 = ReservationTestData.inCartReservation();
			var reservation2 = ReservationTestData.inCartReservation();
			reservation2.setTelegramMsgId(null);

			when(browser.clearCart()).thenReturn(CartClearResult.of(2, 0));
			when(reservationPort.findByStatus(ReservationStatus.IN_CART))
				.thenReturn(List.of(reservation1, reservation2));

			var result = cartService.clearCart("pat");

			assertThat(result.browserRemovedCount()).isEqualTo(2);
			assertThat(result.reservationsUpdatedCount()).isEqualTo(2);
			assertThat(reservation1.status()).isEqualTo(ReservationStatus.REJECTED);
			assertThat(reservation2.status()).isEqualTo(ReservationStatus.REJECTED);
			verify(browser).clearCart();
			verify(reservationPort, times(2)).update(any(ProductReservation.class));
			verify(notification).updateGroupMessage(eq(reservation1.telegramMsgId()), contains("cleared"));
		}

		@Test
		@DisplayName("leaves reservations untouched when the basket could not be read")
		void doesNotReleaseReservationsWhenCartUnreadable() {
			var reservation = ReservationTestData.inCartReservation();

			when(browser.clearCart()).thenReturn(CartClearResult.unreadable());
			when(reservationPort.findByStatus(ReservationStatus.IN_CART)).thenReturn(List.of(reservation));

			var result = cartService.clearCart("pat");

			assertThat(result.cartReadable()).isFalse();
			assertThat(result.reservationsUpdatedCount()).isZero();
			assertThat(reservation.status()).isEqualTo(ReservationStatus.IN_CART);
			verify(reservationPort, never()).update(any(ProductReservation.class));
			verifyNoInteractions(notification);
		}

	}

	// ── Helpers ────────────────────────────────────────────────

	private FilterResult autoReserveResult(DiscoveredProduct product, Profile profile, String size, int score) {
		return new FilterResult(product, profile, size, Decision.AUTO_RESERVE, BrandTier.TIER_1, score);
	}

	private FilterResult notifyOnlyResult(DiscoveredProduct product, Profile profile, String size, int score) {
		return new FilterResult(product, profile, size, Decision.NOTIFY_ONLY, BrandTier.TIER_2, score);
	}

	private CartPilotProperties mockProperties(int expiryMinutes) {
		var cart = mock(CartPilotProperties.CartProperties.class);
		when(cart.expiryMinutes()).thenReturn(expiryMinutes);

		var props = mock(CartPilotProperties.class);
		when(props.cart()).thenReturn(cart);
		return props;
	}

}
