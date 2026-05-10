package com.patbaumgartner.zalando.lounge.cartpilot.adapter.in.telegram;

import com.patbaumgartner.zalando.lounge.cartpilot.config.CartPilotProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.meta.api.methods.updates.DeleteWebhook;
import org.telegram.telegrambots.meta.api.methods.updates.SetWebhook;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import org.telegram.telegrambots.webhook.starter.SpringTelegramWebhookBot;

import java.util.List;

@Configuration
@Profile("!test")
@ConditionalOnProperty(name = "cartpilot.telegram.bot-token")
public class TelegramBotConfiguration {

	private static final Logger log = LoggerFactory.getLogger(TelegramBotConfiguration.class);

	@Bean
	public TelegramClient telegramClient(CartPilotProperties properties) {
		return new OkHttpTelegramClient(properties.telegram().botToken());
	}

	@Bean
	public List<SpringTelegramWebhookBot> telegramWebhookBots(TelegramBotHandler handler, TelegramClient client,
			CartPilotProperties props) {
		Runnable setWebhook = () -> {
			try {
				client.execute(SetWebhook.builder().url(props.telegram().webhookUrl()).build());
			}
			catch (TelegramApiException e) {
				throw new RuntimeException("Failed to set webhook", e);
			}
		};
		Runnable deleteWebhook = () -> {
			try {
				client.execute(DeleteWebhook.builder().build());
			}
			catch (TelegramApiException e) {
				log.error("Failed to delete webhook: {}", e.getMessage(), e);
			}
		};
		var bot = SpringTelegramWebhookBot.builder().botPath(props.telegram().botToken()).updateHandler(update -> {
			handler.onUpdateReceived(update);
			return null;
		}).setWebhook(setWebhook).deleteWebhook(deleteWebhook).build();
		return List.of(bot);
	}

}
