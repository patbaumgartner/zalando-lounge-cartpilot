package com.patbaumgartner.zalando.lounge.cartpilot.adapter.in.telegram;

import com.patbaumgartner.zalando.lounge.cartpilot.application.CampaignScannerService;
import com.patbaumgartner.zalando.lounge.cartpilot.application.CartService;
import com.patbaumgartner.zalando.lounge.cartpilot.application.ProfileManagementService;
import com.patbaumgartner.zalando.lounge.cartpilot.config.CartPilotProperties;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.BrandTier;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.Category;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.Decision;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.ProductReservation;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.ReservationStatus;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.port.out.DiscoveredProductPort;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.port.out.NotificationPort;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.port.out.ProductReservationPort;
import com.patbaumgartner.zalando.lounge.cartpilot.testdata.ProductTestData;
import com.patbaumgartner.zalando.lounge.cartpilot.testdata.ProfileTestData;
import com.patbaumgartner.zalando.lounge.cartpilot.testdata.ReservationTestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChatMember;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMember;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("TelegramBotHandler")
class TelegramBotHandlerTest {

	private static final long GROUP_CHAT_ID = -1001234567890L;

	private static final long FOREIGN_CHAT_ID = -1009999999999L;

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
	private NotificationPort notification;

	@Mock
	private TelegramClient telegramClient;

	private TelegramBotHandler handler;

	@BeforeEach
	void setUp() throws TelegramApiException {
		doThrow(new TelegramApiException("not available in test")).when(telegramClient)
			.execute(any(GetChatMember.class));

		handler = new TelegramBotHandler(telegramClient, cartService, scannerService, profileService, reservationPort,
				productPort, notification, testProperties());
	}

	private static CartPilotProperties testProperties() {
		var zalando = new CartPilotProperties.ZalandoProperties("mail@example.ch", "secret", "session/state.json",
				"https://www.zalando-lounge.ch", "https://www.zalando-lounge.ch/event", 60, 5, 60000, 30000, 3, true,
				false, 240000, false, 12000, 60000, 30000, 1000, 1, false, "diagnostics/auth",
				"ws://patchright:3000/cartpilot");
		return new CartPilotProperties(zalando,
				new CartPilotProperties.TelegramProperties("token", String.valueOf(GROUP_CHAT_ID)),
				new CartPilotProperties.CartProperties(20, 15, 2), new CartPilotProperties.SchedulerProperties(
						"0 0 6 * * *", "0 10 6 * * *", "0 */15 * * * *", "Europe/Zurich"));
	}

	// ── Helpers ────────────────────────────────────────────────

	private Update groupTextUpdate(String text) {
		return groupTextUpdate(text, false);
	}

	private Update groupTextUpdate(String text, boolean isBot) {
		return textUpdate(text, isBot, GROUP_CHAT_ID, "group");
	}

	private Update textUpdate(String text, boolean isBot, Long chatId, String chatType) {
		var user = mock(User.class);
		when(user.getIsBot()).thenReturn(isBot);
		when(user.getId()).thenReturn(42L);
		when(user.getUserName()).thenReturn("testuser");

		var chat = mock(Chat.class);
		when(chat.getType()).thenReturn(chatType);
		when(chat.getId()).thenReturn(chatId);

		var message = mock(Message.class);
		when(message.getFrom()).thenReturn(user);
		when(message.hasText()).thenReturn(true);
		when(message.getText()).thenReturn(text);
		when(message.getChat()).thenReturn(chat);
		when(message.getChatId()).thenReturn(chatId);

		var update = mock(Update.class);
		when(update.hasMessage()).thenReturn(true);
		when(update.getMessage()).thenReturn(message);
		when(update.hasCallbackQuery()).thenReturn(false);
		return update;
	}

	private Update callbackUpdate(String callbackData) {
		return callbackUpdate(callbackData, GROUP_CHAT_ID);
	}

	private Update callbackUpdate(String callbackData, Long chatId) {
		var user = mock(User.class);
		when(user.getUserName()).thenReturn("actor");

		var message = mock(Message.class);
		when(message.getChatId()).thenReturn(chatId);

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

	private void makeAdmin() throws TelegramApiException {
		var member = mock(ChatMember.class);
		when(member.getStatus()).thenReturn("administrator");
		doReturn(member).when(telegramClient).execute(any(GetChatMember.class));
	}

	private void assertSentMessageContains(String expectedFragment) {
		var found = mockingDetails(telegramClient).getInvocations()
			.stream()
			.map(invocation -> invocation.getArgument(0))
			.filter(SendMessage.class::isInstance)
			.map(SendMessage.class::cast)
			.map(SendMessage::getText)
			.anyMatch(text -> text != null && text.contains(expectedFragment));
		assertTrue(found, "Expected a sent message containing: " + expectedFragment);
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
			handler.onUpdateReceived(textUpdate("/help", false, 4242L, "private"));

			verifyNoInteractions(cartService, scannerService, profileService, reservationPort);
		}

		@Test
		@DisplayName("ignores commands from a group other than the configured one")
		void ignoresForeignGroupCommands() {
			handler.onUpdateReceived(textUpdate("/status", false, FOREIGN_CHAT_ID, "group"));

			verifyNoInteractions(cartService, scannerService, profileService, reservationPort);
		}

		@Test
		@DisplayName("ignores admin commands from a foreign group even when the sender is admin there")
		void ignoresForeignGroupAdminCommands() throws TelegramApiException {
			makeAdmin();

			handler.onUpdateReceived(textUpdate("/scan", false, FOREIGN_CHAT_ID, "supergroup"));
			handler.onUpdateReceived(textUpdate("/clear", false, FOREIGN_CHAT_ID, "supergroup"));
			handler.onUpdateReceived(textUpdate("/profile deactivate Pat", false, FOREIGN_CHAT_ID, "supergroup"));

			verifyNoInteractions(cartService, scannerService, profileService);
		}

		@Test
		@DisplayName("ignores callbacks originating outside the configured group")
		void ignoresForeignGroupCallbacks() {
			handler.onUpdateReceived(callbackUpdate("buy:42", FOREIGN_CHAT_ID));
			handler.onUpdateReceived(callbackUpdate("skip:42", FOREIGN_CHAT_ID));

			verifyNoInteractions(cartService);
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

	// ── Non-admin commands ─────────────────────────────────────

	@Nested
	@DisplayName("Non-admin commands")
	class NonAdminCommands {

		@Test
		@DisplayName("/status queries reservations in cart")
		void statusCommandQueriesCart() {
			when(reservationPort.findByStatus(any())).thenReturn(List.of());

			handler.onUpdateReceived(groupTextUpdate("/status"));

			verify(reservationPort, atLeastOnce()).findByStatus(any());
		}

		@Test
		@DisplayName("/help replies with command list regardless of admin status")
		void helpRepliesForNonAdmin() throws TelegramApiException {
			handler.onUpdateReceived(groupTextUpdate("/help"));

			assertSentMessageContains("CartPilot Commands");
		}

		@Test
		@DisplayName("/links posts a link list per open status")
		void linksCommandPostsLinkLists() {
			var product = ProductTestData.mammutJacket();
			var profile = ProfileTestData.aProfile().withId(1L).build();
			var blocked = ReservationTestData.aReservation()
				.withProductId(product.id())
				.withProfileId(profile.id())
				.withStatus(ReservationStatus.BLOCKED)
				.build();

			when(profileService.listAll()).thenReturn(List.of(profile));
			when(reservationPort.findByStatusCreatedOn(any(), any())).thenReturn(List.of());
			when(reservationPort.findByStatusCreatedOn(eq(ReservationStatus.BLOCKED), any()))
				.thenReturn(List.of(blocked));
			when(productPort.findById(product.id())).thenReturn(Optional.of(product));

			handler.onUpdateReceived(groupTextUpdate("/links"));

			var captor = ArgumentCaptor.forClass(List.class);
			verify(notification).sendProductLinks(contains("Blocked"), captor.capture());
			assertThat(captor.getValue()).hasSize(1);
		}

		@Test
		@DisplayName("/links reports an empty state when nothing is open")
		void linksCommandReportsEmptyState() {
			when(profileService.listAll()).thenReturn(List.of());
			when(reservationPort.findByStatusCreatedOn(any(), any())).thenReturn(List.of());

			handler.onUpdateReceived(groupTextUpdate("/links"));

			verify(notification).sendGroupMessage(contains("No open items"));
			verify(notification, never()).sendProductLinks(anyString(), anyList());
		}

		@Test
		@DisplayName("/links skips reservations left over from previous days")
		void linksCommandSkipsStaleReservations() {
			var product = ProductTestData.mammutJacket();
			var profile = ProfileTestData.aProfile().withId(1L).build();
			var stale = new ProductReservation(9L, product.id(), profile.id(), "52", Decision.NOTIFY_ONLY,
					ReservationStatus.PENDING, 30, null, null, null, LocalDateTime.now().minusDays(3));

			when(profileService.listAll()).thenReturn(List.of(profile));
			when(reservationPort.findByStatus(any())).thenReturn(List.of());
			when(reservationPort.findByStatus(ReservationStatus.PENDING)).thenReturn(List.of(stale));

			handler.onUpdateReceived(groupTextUpdate("/links"));

			verify(notification).sendGroupMessage(contains("No open items"));
			verify(notification, never()).sendProductLinks(anyString(), anyList());
		}

		@Test
		@DisplayName("/debug reports reservation counts and the live config")
		void debugCommandReportsDiagnostics() throws TelegramApiException {
			when(reservationPort.findByStatus(any())).thenReturn(List.of());
			when(productPort.findByDiscoveredAt(any())).thenReturn(List.of());

			handler.onUpdateReceived(groupTextUpdate("/debug"));

			assertSentMessageContains("Debug");
			assertSentMessageContains("BLOCKED");
			assertSentMessageContains("ws://patchright:3000/cartpilot");
			assertSentMessageContains("Europe/Zurich");
		}

	}

	// ── Admin commands ─────────────────────────────────────────

	@Nested
	@DisplayName("Admin commands")
	class AdminCommands {

		@BeforeEach
		void makeAdminUser() throws TelegramApiException {
			makeAdmin();
		}

		@Test
		@DisplayName("/help replies with full command list")
		void helpRepliesWithCommandList() throws TelegramApiException {
			handler.onUpdateReceived(groupTextUpdate("/help"));

			assertSentMessageContains("CartPilot Commands");
		}

		@Test
		@DisplayName("/status with empty cart replies with empty cart message")
		void statusEmptyCart() throws TelegramApiException {
			when(reservationPort.findByStatus(any())).thenReturn(List.of());

			handler.onUpdateReceived(groupTextUpdate("/status"));

			assertSentMessageContains("empty");
		}

		@Test
		@DisplayName("/scan sends scan message and triggers scanner in background thread")
		void scanTriggersScannerService() throws Exception {
			var latch = new CountDownLatch(1);
			doAnswer(invocation -> {
				latch.countDown();
				return null;
			}).when(scannerService).scan();

			handler.onUpdateReceived(groupTextUpdate("/scan"));

			assertSentMessageContains("scan");
			assertTrue(latch.await(2, TimeUnit.SECONDS), "Expected async scan to be triggered");
		}

		@Test
		@DisplayName("/clear clears cart via service in a background thread and replies with the outcome")
		void clearTriggersCartClear() throws Exception {
			var latch = new CountDownLatch(1);
			when(cartService.clearCart("testuser")).thenAnswer(invocation -> {
				latch.countDown();
				return new CartService.ClearCartResult(2, 2, true);
			});

			handler.onUpdateReceived(groupTextUpdate("/clear"));

			assertTrue(latch.await(2, TimeUnit.SECONDS), "Expected async cart clear to be triggered");
			verify(cartService).clearCart("testuser");
			assertSentMessageContains("Clearing cart");
		}

		@Test
		@DisplayName("/clear reports that nothing was released when the basket could not be read")
		void clearReportsUnreadableBasket() throws Exception {
			var latch = new CountDownLatch(1);
			when(cartService.clearCart("testuser")).thenAnswer(invocation -> {
				latch.countDown();
				return new CartService.ClearCartResult(0, 0, false);
			});

			handler.onUpdateReceived(groupTextUpdate("/clear"));

			assertTrue(latch.await(2, TimeUnit.SECONDS), "Expected async cart clear to be triggered");
			await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertSentMessageContains("could not be read"));
		}

		@Test
		@DisplayName("/profiles lists all profiles by name")
		void profilesListsAll() throws TelegramApiException {
			when(profileService.listAll()).thenReturn(List.of(ProfileTestData.pat()));

			handler.onUpdateReceived(groupTextUpdate("/profiles"));

			assertSentMessageContains("Pat");
		}

		@Nested
		@DisplayName("/profile show")
		class ProfileShow {

			@Test
			@DisplayName("/profile show <name> replies with profile details")
			void showRepliesWithDetails() throws TelegramApiException {
				when(profileService.show("Pat")).thenReturn(ProfileTestData.pat());

				handler.onUpdateReceived(groupTextUpdate("/profile show Pat"));

				assertSentMessageContains("Pat");
			}

			@Test
			@DisplayName("/profile show <unknown> replies with not found error")
			void showUnknownProfileRepliesWithError() throws TelegramApiException {
				when(profileService.show("Ghost"))
					.thenThrow(new ProfileManagementService.ProfileNotFoundException("Ghost"));

				handler.onUpdateReceived(groupTextUpdate("/profile show Ghost"));

				assertSentMessageContains("❌");
			}

		}

		@Nested
		@DisplayName("/profile activate / deactivate")
		class ProfileActivateDeactivate {

			@Test
			@DisplayName("/profile activate <name> calls service and replies with activated")
			void activateRepliesOk() throws TelegramApiException {
				handler.onUpdateReceived(groupTextUpdate("/profile activate Pat"));

				verify(profileService).activate("Pat");
				assertSentMessageContains("activated");
			}

			@Test
			@DisplayName("/profile deactivate <name> calls service and replies with deactivated")
			void deactivateRepliesOk() throws TelegramApiException {
				handler.onUpdateReceived(groupTextUpdate("/profile deactivate Pat"));

				verify(profileService).deactivate("Pat");
				assertSentMessageContains("deactivated");
			}

		}

		@Nested
		@DisplayName("/profile set")
		class ProfileSet {

			@Test
			@DisplayName("/profile set size <name> <category> <size> updates size and replies")
			void setSizeRepliesOk() throws TelegramApiException {
				handler.onUpdateReceived(groupTextUpdate("/profile set size Pat JACKETS 52"));

				verify(profileService).setSize("Pat", Category.JACKETS, "52");
				assertSentMessageContains("Size updated");
			}

			@Test
			@DisplayName("/profile set price <name> <category> <chf> updates max price and replies")
			void setPriceRepliesOk() throws TelegramApiException {
				handler.onUpdateReceived(groupTextUpdate("/profile set price Pat JACKETS 350"));

				verify(profileService).setMaxPrice(eq("Pat"), eq(Category.JACKETS), any());
				assertSentMessageContains("Max price updated");
			}

			@Test
			@DisplayName("/profile set with too few args replies with usage hint")
			void setTooFewArgsRepliesUsage() throws TelegramApiException {
				handler.onUpdateReceived(groupTextUpdate("/profile set size Pat"));

				assertSentMessageContains("Usage");
			}

			@Test
			@DisplayName("/profile set unknown subcommand replies with error")
			void setUnknownSubcommandRepliesError() throws TelegramApiException {
				handler.onUpdateReceived(groupTextUpdate("/profile set unknown Pat JACKETS 52"));

				assertSentMessageContains("Unknown set subcommand");
			}

		}

		@Nested
		@DisplayName("/profile brand")
		class ProfileBrand {

			@Test
			@DisplayName("/profile brand add <name> tier1 <brand> adds brand and replies")
			void brandAddTier1RepliesOk() throws TelegramApiException {
				handler.onUpdateReceived(groupTextUpdate("/profile brand add Pat tier1 Mammut"));

				verify(profileService).addBrand(eq("Pat"), eq(BrandTier.TIER_1), eq("Mammut"));
				assertSentMessageContains("Brand added");
			}

			@Test
			@DisplayName("/profile brand add <name> tier2 <multi-word brand> joins brand tokens")
			void brandAddTier2MultiWordRepliesOk() throws TelegramApiException {
				handler.onUpdateReceived(groupTextUpdate("/profile brand add Pat tier2 Jack Wolfskin"));

				verify(profileService).addBrand(eq("Pat"), eq(BrandTier.TIER_2), eq("Jack Wolfskin"));
				assertSentMessageContains("Brand added");
			}

			@Test
			@DisplayName("/profile brand remove <name> <brand> removes brand and replies")
			void brandRemoveRepliesOk() throws TelegramApiException {
				handler.onUpdateReceived(groupTextUpdate("/profile brand remove Pat Mammut"));

				verify(profileService).removeBrand("Pat", "Mammut");
				assertSentMessageContains("Brand removed");
			}

			@Test
			@DisplayName("/profile brand add with too few args replies with usage hint")
			void brandAddTooFewArgsRepliesUsage() throws TelegramApiException {
				handler.onUpdateReceived(groupTextUpdate("/profile brand add Pat"));

				assertSentMessageContains("Usage");
			}

			@Test
			@DisplayName("/profile brand with only 3 parts replies with usage hint")
			void brandTooFewArgsRepliesUsage() throws TelegramApiException {
				handler.onUpdateReceived(groupTextUpdate("/profile brand remove"));

				assertSentMessageContains("Usage");
			}

		}

		@Nested
		@DisplayName("/profile error and malformed cases")
		class ProfileErrorCases {

			@Test
			@DisplayName("/profile with only 2 parts replies with usage hint")
			void profileTooFewArgsRepliesUsage() throws TelegramApiException {
				handler.onUpdateReceived(groupTextUpdate("/profile show"));

				assertSentMessageContains("Usage");
			}

			@Test
			@DisplayName("/profile unknown subcommand replies with error")
			void profileUnknownSubcommandRepliesError() throws TelegramApiException {
				handler.onUpdateReceived(groupTextUpdate("/profile frobnicate Pat"));

				assertSentMessageContains("Unknown profile subcommand");
			}

		}

	}

	// ── Access control ─────────────────────────────────────────

	@Nested
	@DisplayName("Access control")
	class AccessControl {

		@Test
		@DisplayName("/scan from non-admin replies with admin-only message and never calls scanner")
		void scanNonAdminIsRejected() throws TelegramApiException {
			handler.onUpdateReceived(groupTextUpdate("/scan"));

			assertSentMessageContains("Admin-only");
			verifyNoInteractions(scannerService);
		}

		@Test
		@DisplayName("/clear from non-admin replies with admin-only message")
		void clearNonAdminIsRejected() throws TelegramApiException {
			handler.onUpdateReceived(groupTextUpdate("/clear"));

			assertSentMessageContains("Admin-only");
			verifyNoInteractions(cartService);
		}

		@Test
		@DisplayName("/profiles from non-admin replies with admin-only message")
		void profilesNonAdminIsRejected() throws TelegramApiException {
			handler.onUpdateReceived(groupTextUpdate("/profiles"));

			assertSentMessageContains("Admin-only");
			verifyNoInteractions(profileService);
		}

		@Test
		@DisplayName("/profile <subcommand> from non-admin replies with admin-only message")
		void profileSubcommandNonAdminIsRejected() throws TelegramApiException {
			handler.onUpdateReceived(groupTextUpdate("/profile show Pat"));

			assertSentMessageContains("Admin-only");
			verifyNoInteractions(profileService);
		}

	}

}
