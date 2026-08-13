package com.patbaumgartner.zalando.lounge.cartpilot.application;

import com.patbaumgartner.zalando.lounge.cartpilot.config.CartPilotProperties;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.CartAddResult;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.Decision;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.FilterResult;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.ProductReservation;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.PurchasedItem;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.ReservationStatus;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.port.out.BrowserPort;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.port.out.DiscoveredProductPort;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.port.out.NotificationPort;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.port.out.ProductReservationPort;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.port.out.ProfilePort;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.port.out.PurchasedItemPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * Manages the cart lifecycle (UC-03 and the Buy/Skip callback handling).
 *
 * <p>
 * Responsibilities:
 * <ul>
 * <li>Add Tier-1 items to the cart via Playwright.</li>
 * <li>Persist NOTIFY_ONLY reservations without touching the browser.</li>
 * <li>Handle Buy / Skip Telegram callbacks.</li>
 * </ul>
 */
@Service
public class CartService {

	private static final Logger log = LoggerFactory.getLogger(CartService.class);

	private final BrowserPort browser;

	private final ProductReservationPort reservationPort;

	private final DiscoveredProductPort productPort;

	private final ProfilePort profilePort;

	private final PurchasedItemPort purchasedItemPort;

	private final NotificationPort notification;

	private final CartPilotProperties properties;

	private final BrowserGate browserGate;

	public CartService(BrowserPort browser, ProductReservationPort reservationPort, DiscoveredProductPort productPort,
			ProfilePort profilePort, PurchasedItemPort purchasedItemPort, NotificationPort notification,
			CartPilotProperties properties, BrowserGate browserGate) {
		this.browser = browser;
		this.reservationPort = reservationPort;
		this.productPort = productPort;
		this.profilePort = profilePort;
		this.purchasedItemPort = purchasedItemPort;
		this.notification = notification;
		this.properties = properties;
		this.browserGate = browserGate;
	}

	/**
	 * Adds a Tier-1 product to the cart and posts an immediate notification. A bot-wall
	 * rejection is recorded as {@link ReservationStatus#BLOCKED} rather than sold out, so
	 * the item stays on the link list for a manual grab.
	 */
	public CartAddResult addToCart(FilterResult result) {
		return browserGate.runExclusively("cart add", () -> doAddToCart(result));
	}

	private CartAddResult doAddToCart(FilterResult result) {
		var product = result.product();
		var profile = result.profile();

		var reservation = ProductReservation.pending(product.id(), profile.id(), result.size(), Decision.AUTO_RESERVE,
				result.score());

		var addResult = browser.addToCart(product.productUrl(), result.size());
		if (!addResult.isAdded()) {
			if (addResult.isBlocked()) {
				log.warn("Bot protection blocked {} (size {}) — {}", product.name(), result.size(),
						addResult.describe());
				reservation.markBlocked();
			}
			else {
				log.warn("Could not confirm {} (size {}) in cart — {}", product.name(), result.size(),
						addResult.describe());
				reservation.markOutOfStock();
			}
			reservationPort.save(reservation);
			return addResult;
		}

		int expiryMinutes = properties.cart().expiryMinutes();
		reservation.markInCart(LocalDateTime.now(), expiryMinutes);
		var saved = reservationPort.save(reservation);

		int msgId = notification.sendReservationNotification(saved, profile, product);
		saved.setTelegramMsgId(msgId);
		reservationPort.update(saved);

		log.atInfo()
			.addArgument(() -> product.name())
			.addArgument(() -> profile.name())
			.addArgument(expiryMinutes)
			.log("Cart: added {} for {} (expires in {} min)");
		return addResult;
	}

	/** Persists a NOTIFY_ONLY reservation (no browser interaction). */
	public void reserveForNotification(FilterResult result) {
		var reservation = ProductReservation.pending(result.product().id(), result.profile().id(), result.size(),
				Decision.NOTIFY_ONLY, result.score());
		reservationPort.save(reservation);
	}

	/**
	 * Handles the Telegram [🛍 Buy] callback (UC-04). Sends the checkout deep-link and
	 * marks purchase initiated.
	 */
	public void handleBuy(Long reservationId, String actorUsername) {
		var reservation = reservationPort.findById(reservationId)
			.orElseThrow(() -> new IllegalArgumentException("Unknown reservation: " + reservationId));

		var product = productPort.findById(reservation.productId())
			.orElseThrow(() -> new IllegalStateException("Product not found"));

		var profile = profilePort.findAll()
			.stream()
			.filter(p -> p.id().equals(reservation.profileId()))
			.findFirst()
			.orElseThrow(() -> new IllegalStateException("Profile not found"));

		reservation.markPurchaseInitiated();
		reservationPort.update(reservation);

		purchasedItemPort.save(PurchasedItem.of(profile.id(), product.id(), actorUsername));

		String msg = "✅ @%s bought %s — CHF %s".formatted(actorUsername, product.name(), product.loungePrice());
		if (reservation.telegramMsgId() != null) {
			notification.updateGroupMessage(reservation.telegramMsgId(), msg);
		}

		// Deep-link: send checkout URL to the group
		notification.sendGroupMessage("🛒 Checkout: " + product.productUrl());
		log.atInfo().addArgument(actorUsername).addArgument(() -> product.name()).log("Buy confirmed by @{} for {}");
	}

	/**
	 * Handles the Telegram [❌ Skip] callback (UC-04). Removes item from cart and marks
	 * reservation as rejected.
	 */
	public void handleSkip(Long reservationId, String actorUsername) {
		browserGate.runExclusively("skip", () -> doHandleSkip(reservationId, actorUsername));
	}

	private void doHandleSkip(Long reservationId, String actorUsername) {
		var reservation = reservationPort.findById(reservationId)
			.orElseThrow(() -> new IllegalArgumentException("Unknown reservation: " + reservationId));

		var product = productPort.findById(reservation.productId())
			.orElseThrow(() -> new IllegalStateException("Product not found"));

		if (reservation.isInCart()) {
			try {
				browser.removeFromCart(product.productUrl());
			}
			catch (Exception e) {
				log.error("Could not remove {} from cart: {}", product.name(), e.getMessage(), e);
			}
		}

		reservation.reject();
		reservationPort.update(reservation);

		String msg = "❌ @%s skipped %s".formatted(actorUsername, product.name());
		if (reservation.telegramMsgId() != null) {
			notification.updateGroupMessage(reservation.telegramMsgId(), msg);
		}
		log.atInfo().addArgument(actorUsername).addArgument(() -> product.name()).log("Skip by @{} for {}");
	}

	/**
	 * Clears the full browser cart and marks all IN_CART reservations as REJECTED.
	 */
	public ClearCartResult clearCart(String actorUsername) {
		return browserGate.runExclusively("clear cart", () -> doClearCart(actorUsername));
	}

	private ClearCartResult doClearCart(String actorUsername) {
		int browserRemoved = browser.clearCart();
		var inCartReservations = reservationPort.findByStatus(ReservationStatus.IN_CART);

		int updatedReservations = 0;
		for (var reservation : inCartReservations) {
			reservation.reject();
			reservationPort.update(reservation);
			updatedReservations++;
			if (reservation.telegramMsgId() != null) {
				notification.updateGroupMessage(reservation.telegramMsgId(),
						"🧹 @%s cleared this reservation from cart".formatted(actorUsername));
			}
		}

		log.atInfo()
			.addArgument(actorUsername)
			.addArgument(browserRemoved)
			.addArgument(updatedReservations)
			.log("Cart cleared by @{} (browser removed: {}, reservations updated: {})");

		return new ClearCartResult(browserRemoved, updatedReservations);
	}

	public record ClearCartResult(int browserRemovedCount, int reservationsUpdatedCount) {
	}

}
