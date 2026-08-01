package com.patbaumgartner.zalando.lounge.cartpilot.domain.port.out;

import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.DiscoveredProduct;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.FilterResult;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.ProductReservation;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.Profile;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.ReservationStatus;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

/**
 * Notification port — sends messages to the Telegram group. Returns the Telegram message
 * ID so it can be stored for later editing.
 */
public interface NotificationPort {

	/**
	 * Posts an "item reserved" notification.
	 * @return the Telegram message ID of the posted message
	 */
	int sendReservationNotification(ProductReservation reservation, Profile profile, DiscoveredProduct product);

	/** Edits an existing group message (e.g. after Buy / Skip action). */
	void updateGroupMessage(int messageId, String text);

	/** Posts the morning summary. */
	void sendMorningSummary(MorningSummary summary);

	/** Posts a plain text message to the group. */
	void sendGroupMessage(String text);

	/**
	 * Posts a clickable list of matched products under the given heading. Long lists are
	 * split across several messages so nothing is silently dropped by Telegram's
	 * per-message size limit.
	 */
	void sendProductLinks(String heading, List<ProductLink> entries);

	/** Posts the per-run scan diagnostics. */
	void sendScanReport(ScanReport report);

	record MorningSummary(LocalDate date, List<FilterResult> autoReserved, List<FilterResult> blocked,
			List<FilterResult> notifyOnly, int campaignCount) {
	}

	/**
	 * One row of a Telegram link list: enough to recognise the item and one tap away from
	 * buying it.
	 *
	 * @param note short status suffix such as {@code "expires 07:41"} or
	 * {@code "blocked (HTTP 403)"}; may be blank
	 */
	record ProductLink(String profileName, String brand, String productName, String size, BigDecimal price,
			String productUrl, ReservationStatus status, String note) {

		public static ProductLink of(FilterResult result, ReservationStatus status, String note) {
			return new ProductLink(result.profile().name(), result.product().brand(), result.product().name(),
					result.size(), result.product().loungePrice(), result.product().productUrl(), status, note);
		}

		public static ProductLink of(ProductReservation reservation, String profileName, DiscoveredProduct product,
				String note) {
			return new ProductLink(profileName, product.brand(), product.name(), reservation.size(),
					product.loungePrice(), product.productUrl(), reservation.status(), note);
		}
	}

	/**
	 * Everything an operator needs to tell a quiet scan from a broken one, without
	 * reading container logs.
	 */
	record ScanReport(LocalDate date, Duration duration, int campaignCount, int productCount, int candidateCount,
			int detailFetchCount, int activeProfileCount, int matchCount, int reservedCount, int blockedCount,
			int unavailableCount, int failedCount, int notifyCount, List<String> notes) {
	}

}
