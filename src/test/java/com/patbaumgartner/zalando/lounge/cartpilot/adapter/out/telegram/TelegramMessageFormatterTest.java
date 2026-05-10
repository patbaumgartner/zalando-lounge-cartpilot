package com.patbaumgartner.zalando.lounge.cartpilot.adapter.out.telegram;

import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.BrandTier;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.Decision;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.FilterResult;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.port.out.NotificationPort.MorningSummary;
import com.patbaumgartner.zalando.lounge.cartpilot.testdata.ProductTestData;
import com.patbaumgartner.zalando.lounge.cartpilot.testdata.ProfileTestData;
import com.patbaumgartner.zalando.lounge.cartpilot.testdata.ReservationTestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TelegramMessageFormatter")
class TelegramMessageFormatterTest {

	private TelegramMessageFormatter formatter;

	@BeforeEach
	void setUp() {
		formatter = new TelegramMessageFormatter("https://www.zalando-lounge.ch");
	}

	@Test
	@DisplayName("reservation notification contains product brand, name and profile")
	void reservationNotificationContainsKeyInfo() {
		var product = ProductTestData.mammutJacket();
		var profile = ProfileTestData.pat();
		var reservation = ReservationTestData.inCartReservation();

		var message = formatter.reservationNotification(reservation, profile, product);

		assertThat(message).contains("Pat")
			.contains("Mammut")
			.contains("Convey Tour 45")
			.contains("52")
			.contains("CHF");
	}

	@Test
	@DisplayName("morning summary shows auto-reserved and notify-only sections")
	void morningSummaryShowsBothSections() {
		var product1 = ProductTestData.mammutJacket();
		var product2 = ProductTestData.jackWolfskinFleece();
		var profile = ProfileTestData.pat();

		var autoReserved = List
			.of(new FilterResult(product1, profile, "52", Decision.AUTO_RESERVE, BrandTier.TIER_1, 70));
		var notifyOnly = List.of(new FilterResult(product2, profile, "52", Decision.NOTIFY_ONLY, BrandTier.TIER_2, 40));

		var summary = new MorningSummary(LocalDate.now(), autoReserved, notifyOnly, 2);
		var message = formatter.morningSummary(summary);

		assertThat(message).contains("Auto-reserved")
			.contains("Review manually")
			.contains("Mammut")
			.contains("Jack Wolfskin");
	}

	@Test
	@DisplayName("morning summary shows no-match message when both lists are empty")
	void morningSummaryShowsNoMatchWhenEmpty() {
		var summary = new MorningSummary(LocalDate.now(), List.of(), List.of(), 0);
		var message = formatter.morningSummary(summary);

		assertThat(message).contains("No matching items today");
	}

	@Test
	@DisplayName("cartStatusLine formats IN_CART status correctly")
	void cartStatusLineFormatsInCart() {
		var product = ProductTestData.mammutJacket();
		var profile = ProfileTestData.pat();
		var reservation = ReservationTestData.inCartReservation();

		var line = formatter.cartStatusLine(reservation, profile, product);

		assertThat(line).contains("Pat").contains("Convey Tour 45").contains("⏳");
	}

}
