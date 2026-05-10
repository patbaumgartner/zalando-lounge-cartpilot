package com.patbaumgartner.zalando.lounge.cartpilot.adapter.in.http;

import com.patbaumgartner.zalando.lounge.cartpilot.application.CampaignScannerService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Dev-only endpoint to trigger scans without Telegram wiring.
 */
@RestController
@Profile("dev")
@RequestMapping("/dev/scan")
class DevScanController {

	private final CampaignScannerService scannerService;

	DevScanController(CampaignScannerService scannerService) {
		this.scannerService = scannerService;
	}

	@PostMapping("/run")
	ResponseEntity<Map<String, Object>> runScan(@RequestParam(defaultValue = "false") boolean sync) {
		if (sync) {
			scannerService.scan();
			return ResponseEntity.ok(Map.of("ok", true, "mode", "sync", "message", "Scan completed"));
		}

		Thread.ofVirtual().name("dev-scan-runner").start(scannerService::scan);
		return ResponseEntity.ok(Map.of("ok", true, "mode", "async", "message", "Scan started"));
	}

}
