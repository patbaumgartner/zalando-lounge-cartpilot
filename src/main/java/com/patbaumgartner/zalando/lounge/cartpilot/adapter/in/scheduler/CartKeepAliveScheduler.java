package com.patbaumgartner.zalando.lounge.cartpilot.adapter.in.scheduler;

import com.patbaumgartner.zalando.lounge.cartpilot.application.CartKeepAliveService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Fires every 15 minutes to keep cart items alive (UC-05).
 */
@Component
public class CartKeepAliveScheduler {

	private static final Logger log = LoggerFactory.getLogger(CartKeepAliveScheduler.class);

	private final CartKeepAliveService keepAliveService;

	public CartKeepAliveScheduler(CartKeepAliveService keepAliveService) {
		this.keepAliveService = keepAliveService;
	}

	@Scheduled(cron = "${cartpilot.scheduler.keep-alive-cron}", zone = "${cartpilot.scheduler.timezone}")
	public void runKeepAlive() {
		log.debug("Cart keep-alive triggered");
		keepAliveService.keepAlive();
	}

}
