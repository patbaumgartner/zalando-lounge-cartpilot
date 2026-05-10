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
 * Keeps cart items alive by removing and re-adding each item every 15 minutes (UC-05).
 *
 * <p>
 * Mutating the basket (remove + re-add) resets Zalando's server-side reservation timer; a
 * read-only presence check does not. This is what actually prolongs the hold.
 *
 * <p>
 * Rules:
 * <ul>
 * <li>Only runs for reservations with status IN_CART.</li>
 * <li>If the item can no longer be re-added (e.g. sold out) → marks EXPIRED and notifies
 * the group.</li>
 * <li>Stops automatically when MAX_KEEP_ALIVE_HOURS has elapsed.</li>
 * </ul>
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
			// Refresh the reservation by removing and re-adding the item. This basket
			// mutation resets Zalando's server-side reservation timer (a read-only check
			// does not), genuinely prolonging the hold up to MAX_KEEP_ALIVE_HOURS. The
			// original cartAddedAt is left untouched, so the cap still measures from the
			// first add.
			boolean stillAvailable = browser.refreshCartItem(product.productUrl(), reservation.size());

			if (!stillAvailable) {
				log.info("Item {} could not be refreshed in cart (reservation {})", product.name(), reservation.id());
				reservation.expire();
				reservationPort.update(reservation);
				notifyExpired(reservation);
			}
			else {
				reservation.renewCartExpiry(expiryMinutes);
				reservationPort.update(reservation);
				log.debug("Refreshed cart hold for reservation {}", reservation.id());
			}
		}
	}

	private void notifyExpired(ProductReservation reservation) {
		productPort.findById(reservation.productId())
			.ifPresent(product -> notification.sendGroupMessage("⌛ %s expired".formatted(product.name())));
	}

}
