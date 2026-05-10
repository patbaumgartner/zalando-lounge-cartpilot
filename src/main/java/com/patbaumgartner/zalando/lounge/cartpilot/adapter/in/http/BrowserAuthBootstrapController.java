package com.patbaumgartner.zalando.lounge.cartpilot.adapter.in.http;

import com.patbaumgartner.zalando.lounge.cartpilot.adapter.out.browser.AuthenticationService;
import com.patbaumgartner.zalando.lounge.cartpilot.config.CartPilotProperties;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.port.out.BrowserPort;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Dev-only endpoint to bootstrap browser authentication and persisted session state.
 */
@RestController
@Profile("dev")
@RequestMapping("/dev/browser")
class BrowserAuthBootstrapController {

	private final BrowserPort browser;

	private final CartPilotProperties properties;

	public BrowserAuthBootstrapController(BrowserPort browser, CartPilotProperties properties) {
		this.browser = browser;
		this.properties = properties;
	}

	@PostMapping("/auth/bootstrap")
	public ResponseEntity<Map<String, Object>> bootstrapAuthentication() {
		var response = new LinkedHashMap<String, Object>();
		var sessionPath = Path.of(properties.zalando().sessionFile());

		try {
			browser.ensureAuthenticated();
			response.put("ok", true);
			response.put("message", "Authentication bootstrap completed");
			response.put("sessionFile", sessionPath.toString());
			response.put("sessionFileExists", Files.exists(sessionPath));
			return ResponseEntity.ok(response);
		}
		catch (Exception e) {
			response.put("ok", false);
			response.put("message", "Authentication bootstrap failed");
			response.put("error", e.getMessage());
			if (e instanceof AuthenticationService.LoginFailedException loginFailedException) {
				response.put("failureCategory", loginFailedException.category().name());
				response.put("diagnosticsPath", loginFailedException.diagnosticsPath() == null ? null
						: loginFailedException.diagnosticsPath().toString());
			}
			response.put("sessionFile", sessionPath.toString());
			response.put("sessionFileExists", Files.exists(sessionPath));
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
		}
	}

}