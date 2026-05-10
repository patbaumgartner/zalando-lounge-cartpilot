package com.patbaumgartner.zalando.lounge.cartpilot.adapter.in.telegram;

import com.patbaumgartner.zalando.lounge.cartpilot.config.CartPilotProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.starter.SpringLongPollingBot;
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer;
import org.telegram.telegrambots.meta.api.methods.updates.DeleteWebhook;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

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
	public SpringLongPollingBot telegramLongPollingBot(TelegramBotHandler handler, TelegramClient client,
			CartPilotProperties props) {
		try {
			client.execute(DeleteWebhook.builder().dropPendingUpdates(false).build());
			log.info("Cleared existing Telegram webhook; starting long-polling");
		}
		catch (TelegramApiException e) {
			log.warn("Could not clear Telegram webhook: {}", e.getMessage());
		}
		return new SpringLongPollingBot() {
			@Override
			public String getBotToken() {
				return props.telegram().botToken();
			}

			@Override
			public LongPollingUpdateConsumer getUpdatesConsumer() {
				return updates -> updates.forEach(handler::onUpdateReceived);
			}
		};
	}

}
