package com.patbaumgartner.zalando.lounge.cartpilot.application;

import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.Decision;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.DiscoveredProduct;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.FilterResult;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.ProductReservation;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.Profile;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.ReservationStatus;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.port.out.DiscoveredProductPort;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.port.out.NotificationPort;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.port.out.ProductReservationPort;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.port.out.ProfilePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Sends the 06:10 morning summary (UC-04, always fired even on zero matches).
 */
@Service
public class MorningSummaryService {

	private static final Logger log = LoggerFactory.getLogger(MorningSummaryService.class);

	private final ProductReservationPort reservationPort;

	private final DiscoveredProductPort productPort;

	private final ProfilePort profilePort;

	private final NotificationPort notification;

	public MorningSummaryService(ProductReservationPort reservationPort, DiscoveredProductPort productPort,
			ProfilePort profilePort, NotificationPort notification) {
		this.reservationPort = reservationPort;
		this.productPort = productPort;
		this.profilePort = profilePort;
		this.notification = notification;
	}

	public void sendSummary() {
		log.atInfo().addArgument(() -> LocalDate.now()).log("Sending morning summary for {}");

		var inCart = reservationPort.findByStatus(ReservationStatus.IN_CART);
		var notifyOnly = reservationPort.findByStatus(ReservationStatus.PENDING);

		var profileIndex = buildProfileIndex();
		var productIndex = buildProductIndex(LocalDate.now());

		var autoReserved = toFilterResults(inCart, profileIndex, productIndex);
		var notifyItems = toFilterResults(notifyOnly, profileIndex, productIndex).stream()
			.filter(fr -> fr.decision() == Decision.NOTIFY_ONLY)
			.toList();

		var campaigns = productPort.findByDiscoveredAt(LocalDate.now());
		var uniqueCampaigns = campaigns.stream().map(DiscoveredProduct::campaignId).distinct().count();

		notification.sendMorningSummary(
				new NotificationPort.MorningSummary(LocalDate.now(), autoReserved, notifyItems, (int) uniqueCampaigns));
	}

	// ── Helpers ────────────────────────────────────────────────

	private Map<Long, Profile> buildProfileIndex() {
		return profilePort.findAll().stream().collect(Collectors.toMap(Profile::id, p -> p));
	}

	private Map<Long, DiscoveredProduct> buildProductIndex(LocalDate date) {
		return productPort.findByDiscoveredAt(date).stream().collect(Collectors.toMap(DiscoveredProduct::id, p -> p));
	}

	private List<FilterResult> toFilterResults(List<ProductReservation> reservations, Map<Long, Profile> profiles,
			Map<Long, DiscoveredProduct> products) {
		var results = new ArrayList<FilterResult>();
		for (var r : reservations) {
			var profile = profiles.get(r.profileId());
			var product = products.get(r.productId());
			if (profile == null || product == null) {
				continue;
			}

			results.add(new FilterResult(product, profile, r.size(), r.decision(), null, r.score()));
		}
		return results;
	}

}
