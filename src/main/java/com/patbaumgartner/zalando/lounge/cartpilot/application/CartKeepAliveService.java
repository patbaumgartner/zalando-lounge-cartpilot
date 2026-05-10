package com.patbaumgartner.zalando.lounge.cartpilot.application;

import com.patbaumgartner.zalando.lounge.cartpilot.config.CartPilotProperties;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.ProductReservation;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.ReservationStatus;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.port.out.BrowserPort;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.port.out.DiscoveredProductPort;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.port.out.NotificationPort;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.port.out.ProductReservationPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * Keeps cart items alive by reloading the cart page every 15 minutes (UC-05).
 *
 * Rules: - Only runs for reservations with status IN_CART. - If the item is gone from the
 * cart → marks EXPIRED and notifies group. - Stops automatically when
 * MAX_KEEP_ALIVE_HOURS has elapsed.
 */
@Service
public class CartKeepAliveService {

	private static final Logger log = LoggerFactory.getLogger(CartKeepAliveService.class);

	private final ProductReservationPort reservationPort;

	private final DiscoveredProductPort productPort;

	private final BrowserPort browser;

	private final NotificationPort notification;

	private final CartPilotProperties properties;

	public CartKeepAliveService(ProductReservationPort reservationPort, DiscoveredProductPort productPort,
			BrowserPort browser, NotificationPort notification, CartPilotProperties properties) {
		this.reservationPort = reservationPort;
		this.productPort = productPort;
		this.browser = browser;
		this.notification = notification;
		this.properties = properties;
	}

	public void keepAlive() {
		var inCartReservations = reservationPort.findByStatus(ReservationStatus.IN_CART);
		if (inCartReservations.isEmpty()) {
			return;
		}

		log.atDebug().addArgument(() -> inCartReservations.size()).log("Keep-alive check for {} cart item(s)");

		int maxHours = properties.cart().maxKeepAliveHours();
		int expiryMinutes = properties.cart().expiryMinutes();

		for (var reservation : inCartReservations) {
			// Stop keep-alive if max duration exceeded
			if (reservation.cartAddedAt() != null) {
				long hoursInCart = ChronoUnit.HOURS.between(reservation.cartAddedAt(), LocalDateTime.now());
				if (hoursInCart >= maxHours) {
					log.info("Max keep-alive reached for reservation {}", reservation.id());
					reservation.expire();
					reservationPort.update(reservation);
					notifyExpired(reservation);
					continue;
				}
			}

			var productOpt = productPort.findById(reservation.productId());
			if (productOpt.isEmpty()) {
				continue;
			}

			var product = productOpt.get();
			boolean stillInCart = browser.isItemInCart(product.productUrl());

			if (!stillInCart) {
				log.info("Item {} no longer in cart (reservation {})", product.name(), reservation.id());
				reservation.expire();
				reservationPort.update(reservation);
				notifyExpired(reservation);
			}
			else {
				reservation.renewCartExpiry(expiryMinutes);
				reservationPort.update(reservation);
				log.debug("Renewed cart expiry for reservation {}", reservation.id());
			}
		}
	}

	private void notifyExpired(ProductReservation reservation) {
		productPort.findById(reservation.productId())
			.ifPresent(product -> notification.sendGroupMessage("⌛ %s expired".formatted(product.name())));
	}

}
