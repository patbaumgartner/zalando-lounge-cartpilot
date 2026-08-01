package com.patbaumgartner.zalando.lounge.cartpilot.adapter.in.telegram;

import com.patbaumgartner.zalando.lounge.cartpilot.application.CampaignScannerService;
import com.patbaumgartner.zalando.lounge.cartpilot.application.CartService;
import com.patbaumgartner.zalando.lounge.cartpilot.application.ProfileManagementService;
import com.patbaumgartner.zalando.lounge.cartpilot.config.CartPilotProperties;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.BrandTier;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.Category;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.DiscoveredProduct;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.ProductReservation;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.Profile;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.ReservationStatus;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.port.out.DiscoveredProductPort;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.port.out.NotificationPort;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.port.out.ProductReservationPort;
import com.patbaumgartner.zalando.lounge.cartpilot.adapter.out.telegram.TelegramMessageFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.ParseMode;
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChatMember;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Handles all inbound Telegram updates: text commands + inline button callbacks.
 *
 * Commands (UC-07): /status, /links, /debug, /scan, /clear, /profiles, /profile …, /help
 *
 * Callbacks: buy:<reservationId>, skip:<reservationId>, view:<reservationId>
 */
@Component
@ConditionalOnBean(TelegramClient.class)
public class TelegramBotHandler {

	private static final Logger log = LoggerFactory.getLogger(TelegramBotHandler.class);

	private final TelegramClient telegramClient;

	private final CartService cartService;

	private final CampaignScannerService scannerService;

	private final ProfileManagementService profileService;

	private final ProductReservationPort reservationPort;

	private final DiscoveredProductPort productPort;

	private final NotificationPort notification;

	private final CartPilotProperties properties;

	public TelegramBotHandler(TelegramClient telegramClient, CartService cartService,
			CampaignScannerService scannerService, ProfileManagementService profileService,
			ProductReservationPort reservationPort, DiscoveredProductPort productPort, NotificationPort notification,
			CartPilotProperties properties) {
		this.telegramClient = telegramClient;
		this.cartService = cartService;
		this.scannerService = scannerService;
		this.profileService = profileService;
		this.reservationPort = reservationPort;
		this.productPort = productPort;
		this.notification = notification;
		this.properties = properties;
	}

	public void onUpdateReceived(Update update) {
		if (update.hasCallbackQuery()) {
			handleCallback(update.getCallbackQuery());
		}
		else if (update.hasMessage() && update.getMessage().hasText()) {
			handleMessage(update.getMessage());
		}
	}

	// ── Command handling ───────────────────────────────────────

	private void handleMessage(Message message) {
		if (message.getFrom().getIsBot()) {
			return;
		}
		if (!isFromGroup(message)) {
			return;
		}

		String text = message.getText().trim();
		String chatId = message.getChatId().toString();
		boolean isAdmin = isAdmin(message);
		handleGroupCommand(text, isAdmin, usernameOf(message.getFrom()), replyText -> reply(chatId, replyText));
	}

	private void handleGroupCommand(String text, boolean isAdmin, String actorUsername, ReplySender replySender) {
		try {
			if (text.startsWith("/help")) {
				replySender.send(helpText());
			}
			else if (text.startsWith("/status")) {
				replySender.send(buildStatusText());
			}
			else if (text.startsWith("/links")) {
				sendLinkLists();
			}
			else if (text.startsWith("/debug")) {
				replySender.send(buildDebugText());
			}
			else if (text.startsWith("/profiles") && isAdmin) {
				replySender.send(buildProfilesText());
			}
			else if (text.startsWith("/profile") && isAdmin) {
				handleProfileCommand(text, replySender);
			}
			else if (text.startsWith("/scan") && isAdmin) {
				replySender.send("🔍 Starting manual scan...");
				Thread.ofVirtual().name("manual-scan").start(scannerService::scan);
			}
			else if (text.startsWith("/clear") && isAdmin) {
				var result = cartService.clearCart(actorUsername);
				replySender.send("🧹 Cleared cart. Browser removed %d item(s); updated %d reservation(s)."
					.formatted(result.browserRemovedCount(), result.reservationsUpdatedCount()));
			}
			else if (!isAdmin && (text.startsWith("/scan") || text.startsWith("/clear") || text.startsWith("/profile")
					|| text.startsWith("/profiles"))) {
				replySender.send("❌ Admin-only command.");
			}
		}
		catch (Exception e) {
			log.error("Error handling command '{}': {}", text, e.getMessage(), e);
			replySender.send("❌ Error: " + e.getMessage());
		}
	}

	private void handleProfileCommand(String text, ReplySender replySender) {
		// /profile show <name>
		// /profile activate <name>
		// /profile deactivate <name>
		// /profile set size <name> <category> <size>
		// /profile set price <name> <category> <chf>
		// /profile brand add <name> <tier> <brand>
		// /profile brand remove <name> <brand>
		var parts = text.split("\\s+");

		if (parts.length < 3) {
			replySender.send("Usage: /profile <show|activate|deactivate|set|brand> ...");
			return;
		}

		try {
			switch (parts[1].toLowerCase()) {
				case "show" -> replySender.send(formatProfile(profileService.show(parts[2])));
				case "activate" -> {
					profileService.activate(parts[2]);
					replySender.send("✅ Profile " + parts[2] + " activated.");
				}
				case "deactivate" -> {
					profileService.deactivate(parts[2]);
					replySender.send("✅ Profile " + parts[2] + " deactivated.");
				}
				case "set" -> handleProfileSet(parts, replySender);
				case "brand" -> handleProfileBrand(parts, replySender);
				default -> replySender.send("Unknown profile subcommand: " + parts[1]);
			}
		}
		catch (ProfileManagementService.ProfileNotFoundException e) {
			replySender.send("❌ Profile not found: " + e.getMessage());
		}
	}

	private void handleProfileSet(String[] parts, ReplySender replySender) {
		// /profile set size <name> <category> <size>
		// /profile set price <name> <category> <chf>
		if (parts.length < 6) {
			replySender.send(
					"Usage: /profile set size <name> <category> <size> OR /profile set price <name> <category> <chf>");
			return;
		}
		String subCmd = parts[2].toLowerCase();
		String name = parts[3];
		Category category = Category.fromString(parts[4]);
		if ("size".equals(subCmd)) {
			profileService.setSize(name, category, parts[5]);
			replySender.send("✅ Size updated: %s %s = %s".formatted(name, category, parts[5]));
		}
		else if ("price".equals(subCmd)) {
			profileService.setMaxPrice(name, category, new BigDecimal(parts[5]));
			replySender.send("✅ Max price updated: %s %s = CHF %s".formatted(name, category, parts[5]));
		}
		else {
			replySender.send("Unknown set subcommand: " + subCmd);
		}
	}

	private void handleProfileBrand(String[] parts, ReplySender replySender) {
		// /profile brand add <name> <tier> <brand>
		// /profile brand remove <name> <brand>
		if (parts.length < 5) {
			replySender.send("Usage: /profile brand add <name> <tier1|tier2> <brand> OR remove <name> <brand>");
			return;
		}
		String action = parts[2].toLowerCase();
		String name = parts[3];
		if ("add".equals(action) && parts.length >= 6) {
			var tier = "tier1".equalsIgnoreCase(parts[4]) ? BrandTier.TIER_1 : BrandTier.TIER_2;
			var brand = String.join(" ", Arrays.copyOfRange(parts, 5, parts.length));
			profileService.addBrand(name, tier, brand);
			replySender.send("✅ Brand added: %s → %s (%s)".formatted(brand, name, tier));
		}
		else if ("remove".equals(action)) {
			var brand = String.join(" ", Arrays.copyOfRange(parts, 4, parts.length));
			profileService.removeBrand(name, brand);
			replySender.send("✅ Brand removed: %s from %s".formatted(brand, name));
		}
		else {
			replySender.send("Usage: /profile brand add|remove ...");
		}
	}

	// ── Callback handling ──────────────────────────────────────

	private void handleCallback(CallbackQuery callback) {
		String data = callback.getData();
		String actor = usernameOf(callback.getFrom());
		String callbackId = callback.getId();
		String chatId = callback.getMessage().getChatId().toString();

		try {
			if (data.startsWith("buy:")) {
				long reservationId = Long.parseLong(data.substring(4));
				cartService.handleBuy(reservationId, actor);
				answerCallback(callbackId, "✅ Purchase initiated!");
			}
			else if (data.startsWith("skip:")) {
				long reservationId = Long.parseLong(data.substring(5));
				cartService.handleSkip(reservationId, actor);
				answerCallback(callbackId, "❌ Skipped.");
			}
			else if (data.startsWith("view:")) {
				long reservationId = Long.parseLong(data.substring(5));
				var reservation = reservationPort.findById(reservationId);
				reservation.flatMap(r -> productPort.findById(r.productId()))
					.ifPresent(p -> reply(chatId, "🔗 " + p.productUrl()));
				answerCallback(callbackId, "");
			}
		}
		catch (Exception e) {
			log.error("Error handling callback '{}': {}", data, e.getMessage(), e);
			answerCallback(callbackId, "❌ Error: " + e.getMessage());
		}
	}

	// ── Formatting helpers ─────────────────────────────────────

	private void sendLinkLists() {
		var profileNames = profileNameIndex();
		var inCart = linksFor(ReservationStatus.IN_CART, profileNames, "in cart");
		var blocked = linksFor(ReservationStatus.BLOCKED, profileNames, "bot protection refused the add");
		var pending = linksFor(ReservationStatus.PENDING, profileNames, "notify only");

		if (inCart.isEmpty() && blocked.isEmpty() && pending.isEmpty()) {
			notification.sendGroupMessage("📭 No open items right now. Run /scan to look for new ones.");
			return;
		}

		if (!inCart.isEmpty()) {
			notification.sendProductLinks("Reserved — links stay valid after the hold expires", inCart);
		}
		if (!blocked.isEmpty()) {
			notification.sendProductLinks("Blocked by bot protection — grab these manually", blocked);
		}
		if (!pending.isEmpty()) {
			notification.sendProductLinks("Matched, notify only", pending);
		}
	}

	private List<NotificationPort.ProductLink> linksFor(ReservationStatus status, Map<Long, String> profileNames,
			String note) {
		var entries = new ArrayList<NotificationPort.ProductLink>();
		for (var reservation : reservationPort.findByStatus(status)) {
			// Reservations are never purged, so without a date bound the list would grow
			// with every past scan and bury today's actually-buyable items.
			if (!isFromToday(reservation)) {
				continue;
			}
			productPort.findById(reservation.productId())
				.ifPresent(product -> entries.add(NotificationPort.ProductLink.of(reservation,
						profileNames.getOrDefault(reservation.profileId(), "unknown"), product,
						noteFor(reservation, note))));
		}
		return entries;
	}

	private static boolean isFromToday(ProductReservation reservation) {
		return reservation.createdAt() != null && reservation.createdAt().toLocalDate().equals(LocalDate.now());
	}

	private String noteFor(ProductReservation reservation, String fallback) {
		if (reservation.status() == ReservationStatus.IN_CART && reservation.cartExpiresAt() != null) {
			return "expires " + reservation.cartExpiresAt().toLocalTime().withNano(0);
		}
		return fallback;
	}

	private Map<Long, String> profileNameIndex() {
		return profileService.listAll().stream().collect(Collectors.toMap(Profile::id, Profile::name, (a, b) -> a));
	}

	private String buildDebugText() {
		var counts = countsByStatus();
		var zalando = properties.zalando();
		var scheduler = properties.scheduler();

		var sb = new StringBuilder("🔬 <b>Debug</b>\n\n");
		sb.append("<b>Reservations</b>\n");
		for (var status : ReservationStatus.values()) {
			sb.append("  ").append(status).append(": ").append(counts.getOrDefault(status, 0L)).append('\n');
		}
		sb.append("\n<b>Today</b>\n");
		var todaysProducts = productPort.findByDiscoveredAt(LocalDate.now());
		sb.append("  Products discovered: ").append(todaysProducts.size()).append('\n');
		sb.append("  Campaigns: ")
			.append(todaysProducts.stream().map(DiscoveredProduct::campaignId).distinct().count())
			.append('\n');

		sb.append("\n<b>Config</b>\n");
		sb.append("  Base URL: ").append(zalando.baseUrl()).append('\n');
		sb.append("  Browser endpoint: ").append(zalando.browserWsEndpoint()).append('\n');
		sb.append("  Headless: ").append(zalando.headless()).append('\n');
		sb.append("  Scan cron: ").append(scheduler.scanCron()).append(" (").append(scheduler.timezone()).append(")\n");
		sb.append("  Summary cron: ").append(scheduler.summaryCron()).append('\n');
		sb.append("  Keep-alive cron: ").append(scheduler.keepAliveCron()).append('\n');
		sb.append("  Cart expiry: ").append(properties.cart().expiryMinutes()).append(" min\n");
		sb.append("  Max keep-alive: ").append(properties.cart().maxKeepAliveHours()).append(" h");
		return sb.toString();
	}

	private Map<ReservationStatus, Long> countsByStatus() {
		return Arrays.stream(ReservationStatus.values())
			.collect(Collectors.toMap(Function.identity(), status -> (long) reservationPort.findByStatus(status).size(),
					(a, b) -> a));
	}

	private String buildStatusText() {
		var items = reservationPort.findByStatus(ReservationStatus.IN_CART);
		if (items.isEmpty()) {
			return "🛒 Cart is empty.";
		}

		var sb = new StringBuilder("🛒 <b>Current cart items:</b>\n\n");
		for (var r : items) {
			productPort.findById(r.productId()).ifPresent(p -> {
				sb.append("  • ").append(p.name()).append(" (").append(r.size()).append(")");
				if (r.cartExpiresAt() != null) {
					sb.append(" — expires ").append(r.cartExpiresAt().toLocalTime());
				}
				sb.append("\n");
			});
		}
		return sb.toString().trim();
	}

	private String buildProfilesText() {
		var profiles = profileService.listAll();
		var sb = new StringBuilder("👥 <b>Profiles:</b>\n\n");
		for (var p : profiles) {
			sb.append(p.active() ? "✅" : "⏸")
				.append(" <b>")
				.append(p.name())
				.append("</b> (")
				.append(p.gender())
				.append(")\n");
		}
		return sb.toString().trim();
	}

	private String formatProfile(Profile p) {
		var sb = new StringBuilder();
		sb.append("👤 <b>").append(p.name()).append("</b>\n");
		sb.append("Gender: ").append(p.gender()).append("\n");
		sb.append("Active: ").append(p.active() ? "yes" : "no").append("\n");
		sb.append("Sizes:\n");
		p.sizes().forEach((cat, sz) -> sb.append("  ").append(cat).append(": ").append(sz).append("\n"));
		sb.append("Tier 1: ").append(String.join(", ", p.brandTier1())).append("\n");
		sb.append("Tier 2: ").append(String.join(", ", p.brandTier2())).append("\n");
		return sb.toString().trim();
	}

	private String helpText() {
		return """
				<b>CartPilot Commands</b>

				/status — Show current cart items
				/links — Post clickable links for every open item (reserved, blocked, notify)
				/debug — Show reservation counts, today's totals and the live config
				/help — Show this message

				<i>Admin only:</i>
				/scan — Trigger immediate campaign scan
				/clear — Remove all items from cart
				/profiles — List all profiles
				/profile show &lt;name&gt;
				/profile activate|deactivate &lt;name&gt;
				/profile set size &lt;name&gt; &lt;category&gt; &lt;size&gt;
				/profile set price &lt;name&gt; &lt;category&gt; &lt;chf&gt;
				/profile brand add &lt;name&gt; &lt;tier1|tier2&gt; &lt;brand&gt;
				/profile brand remove &lt;name&gt; &lt;brand&gt;
				""".trim();
	}

	// ── Infrastructure helpers ─────────────────────────────────

	private void reply(String chatId, String text) {
		for (var chunk : TelegramMessageFormatter.splitForTelegram(text)) {
			try {
				telegramClient
					.execute(SendMessage.builder().chatId(chatId).text(chunk).parseMode(ParseMode.HTML).build());
			}
			catch (TelegramApiException e) {
				log.error("Failed to send reply ({} chars): {}", chunk.length(), e.getMessage(), e);
			}
		}
	}

	private void answerCallback(String callbackId, String text) {
		try {
			telegramClient.execute(AnswerCallbackQuery.builder().callbackQueryId(callbackId).text(text).build());
		}
		catch (TelegramApiException e) {
			log.error("Failed to answer callback: {}", e.getMessage(), e);
		}
	}

	private boolean isFromGroup(Message message) {
		var type = message.getChat().getType();
		return "group".equals(type) || "supergroup".equals(type);
	}

	private boolean isAdmin(Message message) {
		try {
			var member = telegramClient.execute(GetChatMember.builder()
				.chatId(message.getChatId().toString())
				.userId(message.getFrom().getId())
				.build());
			String status = member.getStatus();
			return "creator".equals(status) || "administrator".equals(status);
		}
		catch (TelegramApiException e) {
			log.error("Could not check admin status: {}", e.getMessage(), e);
			return false;
		}
	}

	private String usernameOf(User user) {
		if (user.getUserName() != null) {
			return user.getUserName();
		}
		return user.getFirstName();
	}

	@FunctionalInterface
	private interface ReplySender {

		void send(String text);

	}

}
