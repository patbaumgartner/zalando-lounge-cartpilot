package com.patbaumgartner.zalando.lounge.cartpilot.adapter.in.scheduler;

import com.patbaumgartner.zalando.lounge.cartpilot.application.MorningSummaryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Sends the morning summary at 06:10 Europe/Zurich (UC-04).
 */
@Component
public class MorningSummaryScheduler {

	private static final Logger log = LoggerFactory.getLogger(MorningSummaryScheduler.class);

	private final MorningSummaryService summaryService;

	public MorningSummaryScheduler(MorningSummaryService summaryService) {
		this.summaryService = summaryService;
	}

	@Scheduled(cron = "${cartpilot.scheduler.summary-cron}", zone = "${cartpilot.scheduler.timezone}")
	public void sendMorningSummary() {
		log.info("Morning summary triggered");
		summaryService.sendSummary();
	}

}
