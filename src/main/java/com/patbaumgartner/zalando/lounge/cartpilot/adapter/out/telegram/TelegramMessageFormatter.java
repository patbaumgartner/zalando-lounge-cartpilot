package com.patbaumgartner.zalando.lounge.cartpilot.adapter.out.telegram;

import com.patbaumgartner.zalando.lounge.cartpilot.config.ZalandoUrls;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.DiscoveredProduct;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.FilterResult;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.ProductReservation;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.Profile;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.port.out.NotificationPort;
import org.springframework.beans.factory.annotation.Value;
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

	private final String baseUrl;

	public TelegramMessageFormatter(@Value("${cartpilot.zalando.base-url}") String baseUrl) {
		this.baseUrl = baseUrl;
	}

	public String reservationNotification(ProductReservation reservation, Profile profile, DiscoveredProduct product) {
		var expiresIn = reservation.cartExpiresAt() != null ? " (auto-renewed up to 2 h)" : "";

		return """
				🛒 <b>Reserved for %s</b>

				🏷 %s %s
				📂 %s  ·  📐 Size %s  ·  💰 CHF %s (was CHF %s, −%d%%)
				⏳ Cart expires in ~20 min%s
				"""
			.formatted(esc(profile.name()), esc(product.brand()), esc(product.name()),
					esc(titleCase(product.category().name())), esc(reservation.size()), product.loungePrice(),
					product.originalPrice(), product.discountPct(), expiresIn)
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
		sb.append("\n\n🛒 ").append(link(cartUrl(), "Open basket"));
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
		return "%s %s | %s – CHF %s".formatted(statusIcon, esc(profile.name()),
				link(product.productUrl(), product.name()), product.loungePrice());
	}

	// ── Helpers ────────────────────────────────────────────────

	private String formatSummaryLine(FilterResult item) {
		String tier = item.brandTier() != null ? " (Tier " + item.brandTier().name().replace("TIER_", "") + ")" : "";
		return "%s | %s %s – CHF %s%s".formatted(esc(item.profile().name()), esc(item.product().brand()),
				link(item.product().productUrl(), item.product().name()), item.product().loungePrice(), tier);
	}

	/**
	 * Builds a Telegram HTML anchor, resolving relative URLs and escaping both the URL
	 * and the link text for HTML.
	 */
	private String link(String url, String text) {
		return "<a href=\"" + esc(ZalandoUrls.resolveUrl(baseUrl, url)) + "\">" + esc(text) + "</a>";
	}

	private String cartUrl() {
		return ZalandoUrls.cartUrl(baseUrl);
	}

	/** Escapes the characters Telegram's HTML parse mode treats as markup. */
	private static String esc(String s) {
		if (s == null) {
			return "";
		}
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
	}

	private String titleCase(String s) {
		if (s == null || s.isEmpty()) {
			return s;
		}
		return Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase();
	}

}
