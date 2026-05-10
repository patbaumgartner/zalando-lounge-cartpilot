package com.patbaumgartner.zalando.lounge.cartpilot.domain.port.out;

import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.DiscoveredProduct;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.FilterResult;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.ProductReservation;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.Profile;

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

	record MorningSummary(LocalDate date, List<FilterResult> autoReserved, List<FilterResult> notifyOnly,
			int campaignCount) {
	}

}
