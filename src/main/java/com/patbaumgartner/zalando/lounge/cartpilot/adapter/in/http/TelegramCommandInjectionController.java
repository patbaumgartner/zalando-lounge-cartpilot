package com.patbaumgartner.zalando.lounge.cartpilot.adapter.in.http;

import com.patbaumgartner.zalando.lounge.cartpilot.adapter.in.telegram.TelegramBotHandler;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Dev-only endpoint to inject Telegram-like commands without using Telegram.
 */
@RestController
@Profile("dev")
@RequestMapping("/dev/telegram")
class TelegramCommandInjectionController {

	private final TelegramBotHandler telegramBotHandler;

	public TelegramCommandInjectionController(TelegramBotHandler telegramBotHandler) {
		this.telegramBotHandler = telegramBotHandler;
	}

	@PostMapping("/command")
	public ResponseEntity<Map<String, Object>> injectCommand(@RequestBody InjectCommandRequest request) {
		if (request == null || request.text() == null || request.text().isBlank()) {
			return ResponseEntity.badRequest().body(Map.of("error", "text is required"));
		}

		var asAdmin = Boolean.TRUE.equals(request.asAdmin());
		var result = telegramBotHandler.onInjectedGroupCommand(request.text(), asAdmin);
		var response = new LinkedHashMap<String, Object>();
		response.put("ok", result.ok());
		response.put("text", request.text());
		response.put("asAdmin", asAdmin);
		response.put("replies", result.replies());
		response.put("error", result.error());

		return ResponseEntity.ok(response);
	}

	public record InjectCommandRequest(String text, Boolean asAdmin) {
	}

}
