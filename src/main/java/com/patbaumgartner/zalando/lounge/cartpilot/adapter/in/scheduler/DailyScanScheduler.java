package com.patbaumgartner.zalando.lounge.cartpilot.adapter.in.scheduler;

import com.patbaumgartner.zalando.lounge.cartpilot.application.CampaignScannerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Triggers the daily campaign scan at 06:00 Europe/Zurich (UC-01).
 */
@Component
public class DailyScanScheduler {

	private static final Logger log = LoggerFactory.getLogger(DailyScanScheduler.class);

	private final CampaignScannerService scannerService;

	public DailyScanScheduler(CampaignScannerService scannerService) {
		this.scannerService = scannerService;
	}

	@Scheduled(cron = "${cartpilot.scheduler.scan-cron}", zone = "${cartpilot.scheduler.timezone}")
	public void runDailyScan() {
		log.info("Scheduled daily scan triggered");
		scannerService.scan();
	}

}
