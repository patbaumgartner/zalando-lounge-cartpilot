package com.patbaumgartner.zalando.lounge.cartpilot.adapter.out.telegram;

import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.DiscoveredProduct;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.FilterResult;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.ProductReservation;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.Profile;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.port.out.NotificationPort;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Formats domain objects into Telegram-ready HTML strings. Pure function — no side
 * effects, fully testable.
 */
@Component
public class TelegramMessageFormatter {

	private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH);

	public String reservationNotification(ProductReservation reservation, Profile profile, DiscoveredProduct product) {
		var expiresIn = reservation.cartExpiresAt() != null ? " (auto-renewed up to 2 h)" : "";

		return """
				🛒 <b>Reserved for %s</b>

				🏷 %s %s
				📂 %s  ·  📐 Size %s  ·  💰 CHF %s (was CHF %s, −%d%%)
				⏳ Cart expires in ~20 min%s
				""".formatted(profile.name(), product.brand(), product.name(), titleCase(product.category().name()),
				reservation.size(), product.loungePrice(), product.originalPrice(), product.discountPct(), expiresIn)
			.trim();
	}

	public String morningSummary(NotificationPort.MorningSummary summary) {
		var sb = new StringBuilder();
		sb.append("📋 <b>CartPilot – ").append(summary.date().format(DATE_FMT)).append("</b>\n\n");

		if (!summary.autoReserved().isEmpty()) {
			sb.append("✅ <b>Auto-reserved (").append(summary.autoReserved().size()).append(" item");
			if (summary.autoReserved().size() > 1) {
				sb.append("s");
			}
			sb.append("):</b>\n");
			for (var item : summary.autoReserved()) {
				sb.append("  • ").append(formatSummaryLine(item)).append("\n");
			}
			sb.append("\n");
		}

		if (!summary.notifyOnly().isEmpty()) {
			sb.append("👀 <b>Review manually (").append(summary.notifyOnly().size()).append(" item");
			if (summary.notifyOnly().size() > 1) {
				sb.append("s");
			}
			sb.append("):</b>\n");
			for (var item : summary.notifyOnly()) {
				sb.append("  • ").append(formatSummaryLine(item)).append("\n");
			}
			sb.append("\n");
		}

		if (summary.autoReserved().isEmpty() && summary.notifyOnly().isEmpty()) {
			sb.append("📭 No matching items today.\n\n");
		}

		sb.append("📭 New campaigns today: ")
			.append(summary.campaignCount() > 0 ? "Yes (" + summary.campaignCount() + ")" : "No");

		return sb.toString().trim();
	}

	public String cartStatusLine(ProductReservation reservation, Profile profile, DiscoveredProduct product) {
		var statusIcon = switch (reservation.status()) {
			case IN_CART -> "⏳";
			case PURCHASE_INITIATED -> "✅";
			case REJECTED -> "❌";
			case EXPIRED -> "⌛";
			case OUT_OF_STOCK -> "🚫";
			default -> "❓";
		};
		return "%s %s | %s – CHF %s".formatted(statusIcon, profile.name(), product.name(), product.loungePrice());
	}

	// ── Helpers ────────────────────────────────────────────────

	private String formatSummaryLine(FilterResult item) {
		String tier = item.brandTier() != null ? " (Tier " + item.brandTier().name().replace("TIER_", "") + ")" : "";
		return "%s | %s %s – CHF %s%s".formatted(item.profile().name(), item.product().brand(), item.product().name(),
				item.product().loungePrice(), tier);
	}

	private String titleCase(String s) {
		if (s == null || s.isEmpty()) {
			return s;
		}
		return Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase();
	}

}
