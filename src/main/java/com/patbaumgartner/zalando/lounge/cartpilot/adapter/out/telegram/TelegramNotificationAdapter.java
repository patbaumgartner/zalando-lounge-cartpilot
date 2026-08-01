package com.patbaumgartner.zalando.lounge.cartpilot.adapter.out.telegram;

import com.patbaumgartner.zalando.lounge.cartpilot.config.CartPilotProperties;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.DiscoveredProduct;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.ProductReservation;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.Profile;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.port.out.NotificationPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.ParseMode;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.List;

/**
 * Sends messages to the Telegram group via the bot.
 */
@Component
public class TelegramNotificationAdapter implements NotificationPort {

	private static final Logger log = LoggerFactory.getLogger(TelegramNotificationAdapter.class);

	private final TelegramClient sender;

	private final TelegramMessageFormatter formatter;

	private final CartPilotProperties properties;

	public TelegramNotificationAdapter(TelegramClient sender, TelegramMessageFormatter formatter,
			CartPilotProperties properties) {
		this.sender = sender;
		this.formatter = formatter;
		this.properties = properties;
	}

	@Override
	public int sendReservationNotification(ProductReservation reservation, Profile profile, DiscoveredProduct product) {
		String text = formatter.reservationNotification(reservation, profile, product);

		var keyboard = InlineKeyboardMarkup.builder()
			.keyboardRow(new InlineKeyboardRow(button("🛍 Buy", "buy:" + reservation.id()),
					button("❌ Skip", "skip:" + reservation.id())))
			.keyboardRow(new InlineKeyboardRow(
					urlButton("🔗 Product", properties.zalando().resolveUrl(product.productUrl())),
					urlButton("🛒 Basket", properties.zalando().cartUrl())))
			.build();

		var message = SendMessage.builder()
			.chatId(properties.telegram().groupChatId())
			.text(text)
			.parseMode(ParseMode.HTML)
			.replyMarkup(keyboard)
			.build();

		try {
			var sent = sender.execute(message);
			return sent.getMessageId();
		}
		catch (TelegramApiException e) {
			log.error("Failed to send reservation notification: {}", e.getMessage(), e);
			return -1;
		}
	}

	@Override
	public void updateGroupMessage(int messageId, String text) {
		var edit = EditMessageText.builder()
			.chatId(properties.telegram().groupChatId())
			.messageId(messageId)
			.text(text)
			.parseMode(ParseMode.HTML)
			.build();
		try {
			sender.execute(edit);
		}
		catch (TelegramApiException e) {
			log.error("Failed to edit message {}: {}", messageId, e.getMessage(), e);
		}
	}

	@Override
	public void sendMorningSummary(MorningSummary summary) {
		sendGroupMessage(formatter.morningSummary(summary));
	}

	@Override
	public void sendGroupMessage(String text) {
		for (var chunk : TelegramMessageFormatter.splitForTelegram(text)) {
			sendChunk(chunk);
		}
	}

	@Override
	public void sendProductLinks(String heading, List<ProductLink> entries) {
		sendGroupMessage(formatter.productLinks(heading, entries));
	}

	@Override
	public void sendScanReport(ScanReport report) {
		sendGroupMessage(formatter.scanReport(report));
	}

	// ── Helpers ────────────────────────────────────────────────

	private void sendChunk(String text) {
		var message = SendMessage.builder()
			.chatId(properties.telegram().groupChatId())
			.text(text)
			.parseMode(ParseMode.HTML)
			.disableWebPagePreview(true)
			.build();
		try {
			sender.execute(message);
		}
		catch (TelegramApiException e) {
			log.error("Failed to send group message ({} chars): {}", text.length(), e.getMessage(), e);
		}
	}

	private InlineKeyboardButton button(String label, String callbackData) {
		return InlineKeyboardButton.builder().text(label).callbackData(callbackData).build();
	}

	private InlineKeyboardButton urlButton(String label, String url) {
		return InlineKeyboardButton.builder().text(label).url(url).build();
	}

}
