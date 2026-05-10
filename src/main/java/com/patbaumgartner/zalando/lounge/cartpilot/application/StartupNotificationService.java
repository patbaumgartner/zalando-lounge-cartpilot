package com.patbaumgartner.zalando.lounge.cartpilot.application;

import com.patbaumgartner.zalando.lounge.cartpilot.domain.port.out.NotificationPort;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

@Service
public class StartupNotificationService {

	private final NotificationPort notification;

	private final Environment environment;

	public StartupNotificationService(NotificationPort notification, Environment environment) {
		this.notification = notification;
		this.environment = environment;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void notifyOnStartup() {
		String[] activeProfiles = environment.getActiveProfiles();
		String profiles = activeProfiles.length == 0 ? "default" : String.join(", ", activeProfiles);
		notification.sendGroupMessage("✅ CartPilot started (profiles: %s)".formatted(profiles));
	}

}
