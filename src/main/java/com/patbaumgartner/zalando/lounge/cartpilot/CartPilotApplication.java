package com.patbaumgartner.zalando.lounge.cartpilot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Spring Boot entry point for CartPilot — a headless Zalando Lounge CH monitor that scans
 * campaigns each morning, reserves matching items per profile, and reports to a shared
 * Telegram group.
 */
@SpringBootApplication
@EnableScheduling
public class CartPilotApplication {

	public static void main(String[] args) {
		SpringApplication.run(CartPilotApplication.class, args);
	}

}
