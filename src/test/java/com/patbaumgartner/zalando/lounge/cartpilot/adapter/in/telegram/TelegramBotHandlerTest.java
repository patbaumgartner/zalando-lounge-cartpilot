package com.patbaumgartner.zalando.lounge.cartpilot.adapter.in.telegram;

import com.patbaumgartner.zalando.lounge.cartpilot.application.CampaignScannerService;
import com.patbaumgartner.zalando.lounge.cartpilot.application.CartService;
import com.patbaumgartner.zalando.lounge.cartpilot.application.ProfileManagementService;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.port.out.DiscoveredProductPort;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.port.out.ProductReservationPort;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.port.out.ProfilePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChatMember;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("TelegramBotHandler")
class TelegramBotHandlerTest {

	@Mock
	private CartService cartService;

	@Mock
	private CampaignScannerService scannerService;

	@Mock
	private ProfileManagementService profileService;

	@Mock
	private ProductReservationPort reservationPort;

	@Mock
	private DiscoveredProductPort productPort;

	@Mock
	private ProfilePort profilePort;

	@Mock
	private TelegramClient telegramClient;

	private TelegramBotHandler handler;

	@BeforeEach
	void setUp() throws TelegramApiException {
		doThrow(new TelegramApiException("not available in test")).when(telegramClient)
			.execute(any(GetChatMember.class));

		handler = new TelegramBotHandler(telegramClient, cartService, scannerService, profileService, reservationPort,
				productPort, profilePort);
	}

	// ── Helpers ────────────────────────────────────────────────

	private Update groupTextUpdate(String text) {
		return groupTextUpdate(text, false);
	}

	private Update groupTextUpdate(String text, boolean isBot) {
		var user = mock(User.class);
		when(user.getIsBot()).thenReturn(isBot);
		when(user.getId()).thenReturn(42L);
		when(user.getUserName()).thenReturn("testuser");

		var chat = mock(Chat.class);
		when(chat.getType()).thenReturn("group");
		when(chat.getId()).thenReturn(-1001234567890L);

		var message = mock(Message.class);
		when(message.getFrom()).thenReturn(user);
		when(message.hasText()).thenReturn(true);
		when(message.getText()).thenReturn(text);
		when(message.getChat()).thenReturn(chat);
		when(message.getChatId()).thenReturn(-1001234567890L);

		var update = mock(Update.class);
		when(update.hasMessage()).thenReturn(true);
		when(update.getMessage()).thenReturn(message);
		when(update.hasCallbackQuery()).thenReturn(false);
		return update;
	}

	private Update callbackUpdate(String callbackData) {
		var user = mock(User.class);
		when(user.getUserName()).thenReturn("actor");

		var message = mock(Message.class);
		when(message.getChatId()).thenReturn(-1001234567890L);

		var callbackQuery = mock(CallbackQuery.class);
		when(callbackQuery.getData()).thenReturn(callbackData);
		when(callbackQuery.getId()).thenReturn("cb-id");
		when(callbackQuery.getFrom()).thenReturn(user);
		when(callbackQuery.getMessage()).thenReturn(message);

		var update = mock(Update.class);
		when(update.hasCallbackQuery()).thenReturn(true);
		when(update.getCallbackQuery()).thenReturn(callbackQuery);
		return update;
	}

	// ── Message filtering ──────────────────────────────────────

	@Nested
	@DisplayName("Message filtering")
	class MessageFiltering {

		@Test
		@DisplayName("ignores messages sent by bots")
		void ignoresBotMessages() {
			var update = groupTextUpdate("/help", true);

			handler.onUpdateReceived(update);

			verifyNoInteractions(cartService, scannerService, profileService, reservationPort);
		}

		@Test
		@DisplayName("ignores messages sent in private chats")
		void ignoresPrivateMessages() {
			var user = mock(User.class);
			when(user.getIsBot()).thenReturn(false);

			var chat = mock(Chat.class);
			when(chat.getType()).thenReturn("private");

			var message = mock(Message.class);
			when(message.getFrom()).thenReturn(user);
			when(message.hasText()).thenReturn(true);
			when(message.getText()).thenReturn("/help");
			when(message.getChat()).thenReturn(chat);

			var update = mock(Update.class);
			when(update.hasCallbackQuery()).thenReturn(false);
			when(update.hasMessage()).thenReturn(true);
			when(update.getMessage()).thenReturn(message);

			handler.onUpdateReceived(update);

			verifyNoInteractions(cartService, scannerService, profileService, reservationPort);
		}

	}

	// ── Callback handling ──────────────────────────────────────

	@Nested
	@DisplayName("Callback handling")
	class CallbackHandling {

		@Test
		@DisplayName("buy callback triggers CartService.handleBuy with reservation id and actor")
		void handlesBuyCallback() {
			handler.onUpdateReceived(callbackUpdate("buy:42"));

			verify(cartService).handleBuy(42L, "actor");
		}

		@Test
		@DisplayName("skip callback triggers CartService.handleSkip with reservation id and actor")
		void handlesSkipCallback() {
			handler.onUpdateReceived(callbackUpdate("skip:99"));

			verify(cartService).handleSkip(99L, "actor");
		}

		@Test
		@DisplayName("view callback looks up reservation and product URL")
		void handlesViewCallback() {
			when(reservationPort.findById(7L)).thenReturn(Optional.empty());

			handler.onUpdateReceived(callbackUpdate("view:7"));

			verify(reservationPort).findById(7L);
		}

	}

	// ── Status command ─────────────────────────────────────────

	@Nested
	@DisplayName("Non-admin commands")
	class NonAdminCommands {

		@Test
		@DisplayName("/status queries reservations in cart")
		void statusCommandQueriesCart() {
			when(reservationPort.findByStatus(any())).thenReturn(List.of());

			handler.onUpdateReceived(groupTextUpdate("/status"));

			// isAdmin() check calls execute() which fails in tests (no real Telegram)
			// but status is not admin-gated so it still runs
			verify(reservationPort, atLeastOnce()).findByStatus(any());
		}

	}

}
