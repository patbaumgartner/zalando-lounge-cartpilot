package com.patbaumgartner.zalando.lounge.cartpilot.application;

import com.patbaumgartner.zalando.lounge.cartpilot.config.CartPilotProperties;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.ProductReservation;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.Profile;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.ReservationStatus;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.port.out.BrowserPort;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.port.out.DiscoveredProductPort;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.port.out.NotificationPort;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.port.out.ProductReservationPort;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.port.out.ProfilePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

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
 * <li>If the item can no longer be re-added (e.g. sold out) → marks EXPIRED and posts a
 * link list so the group can still buy it by hand.</li>
 * <li>If the shop's bot protection refuses the refresh → keeps the reservation IN_CART
 * and reports it, since a block says nothing about availability.</li>
 * <li>Stops automatically when MAX_KEEP_ALIVE_HOURS has elapsed.</li>
 * </ul>
 */
@Service
public class CartKeepAliveService {

	private static final Logger log = LoggerFactory.getLogger(CartKeepAliveService.class);

	private final ProductReservationPort reservationPort;

	private final DiscoveredProductPort productPort;

	private final ProfilePort profilePort;

	private final BrowserPort browser;

	private final NotificationPort notification;

	private final CartPilotProperties properties;

	private final BrowserGate browserGate;

	public CartKeepAliveService(ProductReservationPort reservationPort, DiscoveredProductPort productPort,
			ProfilePort profilePort, BrowserPort browser, NotificationPort notification, CartPilotProperties properties,
			BrowserGate browserGate) {
		this.reservationPort = reservationPort;
		this.productPort = productPort;
		this.profilePort = profilePort;
		this.browser = browser;
		this.notification = notification;
		this.properties = properties;
		this.browserGate = browserGate;
	}

	public void keepAlive() {
		var inCartReservations = reservationPort.findByStatus(ReservationStatus.IN_CART);
		if (inCartReservations.isEmpty()) {
			return;
		}
		// Never queue behind a scan: a scan replaces the whole basket, so by the time it
		// releases the browser these holds are gone and refreshing them is meaningless.
		browserGate.tryRunExclusively("cart keep-alive", () -> refreshAll(inCartReservations));
	}

	private void refreshAll(List<ProductReservation> inCartReservations) {
		log.atDebug().addArgument(() -> inCartReservations.size()).log("Keep-alive check for {} cart item(s)");

		int maxHours = properties.cart().maxKeepAliveHours();
		int expiryMinutes = properties.cart().expiryMinutes();
		var expired = new ArrayList<NotificationPort.ProductLink>();
		var blocked = new ArrayList<NotificationPort.ProductLink>();

		for (var reservation : inCartReservations) {
			// Stop keep-alive if max duration exceeded
			if (reservation.cartAddedAt() != null) {
				long hoursInCart = ChronoUnit.HOURS.between(reservation.cartAddedAt(), LocalDateTime.now());
				if (hoursInCart >= maxHours) {
					log.info("Max keep-alive reached for reservation {}", reservation.id());
					reservation.expire();
					reservationPort.update(reservation);
					collectLink(expired, reservation, "held for %d h — hold released".formatted(hoursInCart));
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
			var refresh = browser.refreshCartItem(product.productUrl(), reservation.size());

			if (refresh.isAdded()) {
				reservation.renewCartExpiry(expiryMinutes);
				reservationPort.update(reservation);
				log.debug("Refreshed cart hold for reservation {}", reservation.id());
			}
			else if (refresh.isBlocked()) {
				// A bot-wall rejection says nothing about stock, so writing the
				// reservation off as expired would throw away a hold that may well still
				// stand. Keep it IN_CART, retry next cycle, and hand the group a link.
				log.warn("Keep-alive blocked for reservation {} — {}", reservation.id(), refresh.describe());
				collectLink(blocked, reservation, refresh.describe());
			}
			else {
				log.info("Item {} could not be refreshed in cart (reservation {}): {}", product.name(),
						reservation.id(), refresh.describe());
				reservation.expire();
				reservationPort.update(reservation);
				collectLink(expired, reservation, refresh.detail());
			}
		}

		if (!expired.isEmpty()) {
			notification.sendProductLinks("Reservation ran out — still buyable by hand", expired);
		}
		if (!blocked.isEmpty()) {
			notification.sendProductLinks("Keep-alive blocked by bot protection — check these manually", blocked);
		}
	}

	private void collectLink(List<NotificationPort.ProductLink> target, ProductReservation reservation, String note) {
		productPort.findById(reservation.productId())
			.ifPresent(product -> target.add(
					NotificationPort.ProductLink.of(reservation, profileName(reservation.profileId()), product, note)));
	}

	private String profileName(Long profileId) {
		return profilePort.findAll()
			.stream()
			.filter(p -> p.id().equals(profileId))
			.map(Profile::name)
			.findFirst()
			.orElse("unknown");
	}

}
