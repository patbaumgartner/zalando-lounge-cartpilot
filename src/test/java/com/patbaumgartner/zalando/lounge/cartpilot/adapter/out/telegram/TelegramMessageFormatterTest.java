package com.patbaumgartner.zalando.lounge.cartpilot.adapter.out.telegram;

import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.BrandTier;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.Decision;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.FilterResult;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.ReservationStatus;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.port.out.NotificationPort;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.port.out.NotificationPort.MorningSummary;
import com.patbaumgartner.zalando.lounge.cartpilot.testdata.ProductTestData;
import com.patbaumgartner.zalando.lounge.cartpilot.testdata.ProfileTestData;
import com.patbaumgartner.zalando.lounge.cartpilot.testdata.ReservationTestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
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

		var summary = new MorningSummary(LocalDate.now(), autoReserved, List.of(), notifyOnly, 2);
		var message = formatter.morningSummary(summary);

		assertThat(message).contains("Auto-reserved")
			.contains("Review manually")
			.contains("Mammut")
			.contains("Jack Wolfskin");
	}

	@Test
	@DisplayName("morning summary lists blocked items in their own section")
	void morningSummaryShowsBlockedSection() {
		var product = ProductTestData.mammutJacket();
		var profile = ProfileTestData.pat();
		var blocked = List.of(new FilterResult(product, profile, "52", Decision.AUTO_RESERVE, BrandTier.TIER_1, 70));

		var summary = new MorningSummary(LocalDate.now(), List.of(), blocked, List.of(), 1);
		var message = formatter.morningSummary(summary);

		assertThat(message).contains("Blocked").contains("Mammut").contains("<a href=");
	}

	@Test
	@DisplayName("morning summary shows no-match message when all lists are empty")
	void morningSummaryShowsNoMatchWhenEmpty() {
		var summary = new MorningSummary(LocalDate.now(), List.of(), List.of(), List.of(), 0);
		var message = formatter.morningSummary(summary);

		assertThat(message).contains("No matching items today");
	}

	@Test
	@DisplayName("product links render one clickable entry per item")
	void productLinksRenderClickableEntries() {
		var product = ProductTestData.mammutJacket();
		var entries = List.of(new NotificationPort.ProductLink("Pat", product.brand(), product.name(), "52",
				product.loungePrice(), product.productUrl(), ReservationStatus.BLOCKED, "HTTP 403"));

		var message = formatter.productLinks("Blocked by bot protection", entries);

		assertThat(message).contains("Blocked by bot protection")
			.contains("<a href=\"" + product.productUrl() + "\">")
			.contains("Pat")
			.contains("52")
			.contains("HTTP 403");
	}

	@Test
	@DisplayName("product links report an empty list instead of rendering nothing")
	void productLinksHandleEmptyList() {
		assertThat(formatter.productLinks("Nothing here", List.of())).contains("Nothing to show");
	}

	@Test
	@DisplayName("scan report lists every diagnostic counter and any notes")
	void scanReportListsCounters() {
		var report = new NotificationPort.ScanReport(LocalDate.now(), Duration.ofSeconds(95), 3, 240, 18, 4, 2, 7, 1, 2,
				1, 0, 3, List.of("Campaign camp-1 failed to scrape: timeout"));

		var message = formatter.scanReport(report);

		assertThat(message).contains("Scan report")
			.contains("1 min 35 s")
			.contains("Campaigns: 3")
			.contains("Products scraped: 240")
			.contains("Blocked by bot protection: 2")
			.contains("Notify only: 3")
			.contains("camp-1 failed to scrape");
	}

	@Test
	@DisplayName("splitForTelegram keeps short messages in a single chunk")
	void splitKeepsShortMessagesIntact() {
		assertThat(TelegramMessageFormatter.splitForTelegram("short message")).containsExactly("short message");
	}

	@Test
	@DisplayName("splitForTelegram chunks a long link list on line boundaries without breaking anchors")
	void splitChunksLongListsOnLineBoundaries() {
		var product = ProductTestData.mammutJacket();
		var entries = new ArrayList<NotificationPort.ProductLink>();
		for (int i = 0; i < 60; i++) {
			entries.add(new NotificationPort.ProductLink("Pat", product.brand(), product.name() + " " + i, "52",
					product.loungePrice(), product.productUrl(), ReservationStatus.PENDING, "notify only"));
		}

		var chunks = TelegramMessageFormatter.splitForTelegram(formatter.productLinks("Matched today", entries));

		assertThat(chunks).hasSizeGreaterThan(1);
		assertThat(chunks).allSatisfy(chunk -> {
			assertThat(chunk.length()).isLessThanOrEqualTo(TelegramMessageFormatter.MAX_MESSAGE_LENGTH);
			assertThat(countOccurrences(chunk, "<a href=")).isEqualTo(countOccurrences(chunk, "</a>"));
		});
		assertThat(String.join("\n", chunks)).contains("Convey Tour 45 59");
	}

	private static int countOccurrences(String haystack, String needle) {
		int count = 0;
		int index = haystack.indexOf(needle);
		while (index >= 0) {
			count++;
			index = haystack.indexOf(needle, index + needle.length());
		}
		return count;
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
