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
 * <li>If the item left the basket and could not be put back → marks EXPIRED and posts a
 * link list so the group can still buy it by hand. This holds even when the re-add was
 * refused by bot protection: once the removal went through, the hold is gone.</li>
 * <li>If the removal itself never went through → the original hold is untouched, so the
 * reservation stays IN_CART and is retried next cycle.</li>
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

			if (refresh.isRefreshed()) {
				reservation.renewCartExpiry(expiryMinutes);
				reservationPort.update(reservation);
				log.debug("Refreshed cart hold for reservation {}", reservation.id());
			}
			else if (refresh.holdSurvived()) {
				// The removal never went through, so the original hold is untouched and
				// the reservation is still live. Retry on the next cycle.
				log.warn("Keep-alive could not refresh reservation {} — {}", reservation.id(), refresh.describe());
				collectLink(blocked, reservation, refresh.describe());
			}
			else {
				// The article did leave the basket and could not be put back, so the
				// hold is gone for good — even when the re-add was refused by the bot
				// wall rather than by stock.
				log.info("Item {} left the basket and could not be re-added (reservation {}): {}", product.name(),
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
