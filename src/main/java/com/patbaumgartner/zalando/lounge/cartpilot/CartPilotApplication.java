package com.patbaumgartner.zalando.lounge.cartpilot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class CartPilotApplication {

	public static void main(String[] args) {
		SpringApplication.run(CartPilotApplication.class, args);
	}

}
