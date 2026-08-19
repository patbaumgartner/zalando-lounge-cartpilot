package com.patbaumgartner.zalando.lounge.cartpilot.adapter.in.scheduler;

import com.patbaumgartner.zalando.lounge.cartpilot.application.CampaignScannerService;
import com.patbaumgartner.zalando.lounge.cartpilot.application.MorningSummaryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Fallback trigger for the daily digest. The scan publishes the digest itself once it
 * finishes, so this only fires on days no scan completed — a scan started minutes earlier
 * is still running here and would otherwise report an empty day.
 */
@Component
public class MorningSummaryScheduler {

	private static final Logger log = LoggerFactory.getLogger(MorningSummaryScheduler.class);

	private final MorningSummaryService summaryService;

	private final CampaignScannerService scannerService;

	public MorningSummaryScheduler(MorningSummaryService summaryService, CampaignScannerService scannerService) {
		this.summaryService = summaryService;
		this.scannerService = scannerService;
	}

	@Scheduled(cron = "${cartpilot.scheduler.summary-cron}", zone = "${cartpilot.scheduler.timezone}")
	public void sendMorningSummary() {
		if (scannerService.isScanInProgress()) {
			log.info("Scan still running — the digest will be sent when it finishes");
			return;
		}
		log.info("Morning summary triggered");
		summaryService.sendSummaryIfNotSentToday();
	}

}
