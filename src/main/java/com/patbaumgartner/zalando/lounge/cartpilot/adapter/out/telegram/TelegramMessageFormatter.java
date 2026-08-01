package com.patbaumgartner.zalando.lounge.cartpilot.adapter.out.telegram;

import com.patbaumgartner.zalando.lounge.cartpilot.config.ZalandoUrls;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.DiscoveredProduct;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.FilterResult;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.ProductReservation;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.Profile;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.ReservationStatus;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.port.out.NotificationPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Formats domain objects into Telegram-ready HTML strings. Pure function — no side
 * effects, fully testable.
 */
@Component
public class TelegramMessageFormatter {

	/** Telegram rejects any {@code sendMessage} whose text exceeds 4096 characters. */
	static final int MAX_MESSAGE_LENGTH = 3900;

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

		appendSection(sb, "✅", "Auto-reserved", summary.autoReserved());
		appendSection(sb, "🛡", "Blocked — grab manually", summary.blocked());
		appendSection(sb, "👀", "Review manually", summary.notifyOnly());

		if (summary.autoReserved().isEmpty() && summary.blocked().isEmpty() && summary.notifyOnly().isEmpty()) {
			sb.append("📭 No matching items today.\n\n");
		}

		sb.append("📭 New campaigns today: ")
			.append(summary.campaignCount() > 0 ? "Yes (" + summary.campaignCount() + ")" : "No");
		sb.append("\n\n🛒 ").append(link(cartUrl(), "Open basket"));
		return sb.toString().trim();
	}

	private void appendSection(StringBuilder sb, String icon, String title, List<FilterResult> items) {
		if (items.isEmpty()) {
			return;
		}
		sb.append(icon).append(" <b>").append(title).append(" (").append(items.size()).append(" item");
		if (items.size() > 1) {
			sb.append("s");
		}
		sb.append("):</b>\n");
		for (var item : items) {
			sb.append("  • ").append(formatSummaryLine(item)).append("\n");
		}
		sb.append("\n");
	}

	public String cartStatusLine(ProductReservation reservation, Profile profile, DiscoveredProduct product) {
		var statusIcon = statusIcon(reservation.status());
		return "%s %s | %s – CHF %s".formatted(statusIcon, esc(profile.name()),
				link(product.productUrl(), product.name()), product.loungePrice());
	}

	/**
	 * Every entry carries its own link, so a blocked or expiring item stays reachable.
	 */
	public String productLinks(String heading, List<NotificationPort.ProductLink> entries) {
		if (entries.isEmpty()) {
			return "🔗 <b>%s</b>\n\n📭 Nothing to show.".formatted(esc(heading));
		}

		var sb = new StringBuilder("🔗 <b>").append(esc(heading)).append("</b> (").append(entries.size()).append(")\n");
		for (var entry : entries) {
			sb.append('\n').append(productLinkLine(entry));
		}
		sb.append("\n\n🛒 ").append(link(cartUrl(), "Open basket"));
		return sb.toString();
	}

	/**
	 * Renders the per-run scan diagnostics an operator would otherwise dig out of logs.
	 */
	public String scanReport(NotificationPort.ScanReport report) {
		var sb = new StringBuilder("🔬 <b>Scan report – ").append(report.date().format(DATE_FMT)).append("</b>\n\n");
		sb.append("⏱ Duration: ").append(formatDuration(report.duration())).append('\n');
		sb.append("📭 Campaigns: ").append(report.campaignCount()).append('\n');
		sb.append("📦 Products scraped: ").append(report.productCount()).append('\n');
		sb.append("🎯 Brand/price candidates: ").append(report.candidateCount()).append('\n');
		sb.append("🔎 Detail lookups: ").append(report.detailFetchCount()).append('\n');
		sb.append("👥 Active profiles: ").append(report.activeProfileCount()).append('\n');
		sb.append("✔️ Matches after all gates: ").append(report.matchCount()).append("\n\n");
		sb.append("✅ Reserved: ").append(report.reservedCount()).append('\n');
		sb.append("🛡 Blocked by bot protection: ").append(report.blockedCount()).append('\n');
		sb.append("🚫 Size gone: ").append(report.unavailableCount()).append('\n');
		sb.append("⚠️ Failed: ").append(report.failedCount()).append('\n');
		sb.append("👀 Notify only: ").append(report.notifyCount());

		if (!report.notes().isEmpty()) {
			sb.append("\n\n🗒 <b>Notes</b>");
			for (var note : report.notes()) {
				sb.append("\n  • ").append(esc(note));
			}
		}
		return sb.toString();
	}

	/**
	 * Splits an HTML message into Telegram-sized chunks on line boundaries. Telegram
	 * rejects anything over 4096 characters outright, which silently swallowed whole
	 * summaries once a scan matched more than a handful of items. Splitting on newlines
	 * keeps every {@code <a>}/{@code <b>} tag intact, since the formatters never span a
	 * tag across lines.
	 */
	public static List<String> splitForTelegram(String text) {
		if (text == null || text.isBlank()) {
			return List.of();
		}
		if (text.length() <= MAX_MESSAGE_LENGTH) {
			return List.of(text);
		}

		var chunks = new ArrayList<String>();
		var current = new StringBuilder();
		for (var line : text.split("\n", -1)) {
			for (var piece : hardSplit(line)) {
				if (!current.isEmpty() && current.length() + 1 + piece.length() > MAX_MESSAGE_LENGTH) {
					chunks.add(current.toString());
					current.setLength(0);
				}
				if (!current.isEmpty()) {
					current.append('\n');
				}
				current.append(piece);
			}
		}
		if (!current.isEmpty()) {
			chunks.add(current.toString());
		}
		return List.copyOf(chunks);
	}

	// ── Helpers ────────────────────────────────────────────────

	/** Last-resort split for a single line that on its own exceeds the Telegram limit. */
	private static List<String> hardSplit(String line) {
		if (line.length() <= MAX_MESSAGE_LENGTH) {
			return List.of(line);
		}
		var pieces = new ArrayList<String>();
		for (int start = 0; start < line.length(); start += MAX_MESSAGE_LENGTH) {
			pieces.add(line.substring(start, Math.min(line.length(), start + MAX_MESSAGE_LENGTH)));
		}
		return pieces;
	}

	private String productLinkLine(NotificationPort.ProductLink entry) {
		var sb = new StringBuilder();
		sb.append(statusIcon(entry.status()))
			.append(' ')
			.append(esc(entry.profileName()))
			.append(" | ")
			.append(esc(entry.brand()))
			.append(' ')
			.append(link(entry.productUrl(), entry.productName()));
		if (entry.size() != null && !entry.size().isBlank()) {
			sb.append(" · 📐 ").append(esc(entry.size()));
		}
		if (entry.price() != null) {
			sb.append(" · CHF ").append(entry.price());
		}
		if (entry.note() != null && !entry.note().isBlank()) {
			sb.append(" · ").append(esc(entry.note()));
		}
		return sb.toString();
	}

	private static String statusIcon(ReservationStatus status) {
		return switch (status) {
			case IN_CART -> "⏳";
			case PURCHASE_INITIATED -> "✅";
			case REJECTED -> "❌";
			case EXPIRED -> "⌛";
			case OUT_OF_STOCK -> "🚫";
			case BLOCKED -> "🛡";
			case PENDING -> "👀";
		};
	}

	private static String formatDuration(Duration duration) {
		if (duration == null) {
			return "n/a";
		}
		long minutes = duration.toMinutes();
		return minutes > 0 ? "%d min %d s".formatted(minutes, duration.toSecondsPart())
				: "%d s".formatted(duration.toSeconds());
	}

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
