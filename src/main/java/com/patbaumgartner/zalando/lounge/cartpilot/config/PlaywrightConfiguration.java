package com.patbaumgartner.zalando.lounge.cartpilot.config;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystems;
import java.util.Collections;

import com.microsoft.playwright.Playwright;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Playwright wiring.
 *
 * <p>
 * No {@code Browser} bean is defined on purpose. Connecting to the Patchright sidecar at
 * startup made the whole application refuse to boot whenever that sidecar was not yet
 * accepting connections — a plain compose start races it, and every sidecar restart took
 * the app down with it. {@code PlaywrightBrowserAdapter} has to reconnect on demand
 * regardless, because the sidecar drops idle connections, so it owns the connection.
 */
@Configuration
public class PlaywrightConfiguration {

	private static final Logger log = LoggerFactory.getLogger(PlaywrightConfiguration.class);

	@Bean
	@Profile("!test")
	public Playwright playwright(CartPilotProperties properties) {
		String ws = properties.zalando().browserWsEndpoint();
		if (ws == null || ws.isBlank()) {
			throw new IllegalStateException("cartpilot.zalando.browser-ws-endpoint must point to the Patchright server "
					+ "(e.g. ws://patchright:3000/cartpilot). Start it via 'docker compose up patchright'.");
		}
		ensureNativeImageResourceFileSystem();
		log.info("Initialising Playwright; Patchright browser server is {}", ws);
		return Playwright.create();
	}

	/**
	 * In a GraalVM native image, Playwright's {@code DriverJar} extracts its bundled
	 * Node.js driver by walking classpath resources through the {@code resource:} NIO
	 * file system, which must be opened explicitly first — otherwise it fails with
	 * {@code FileSystemNotFoundException: The Native Image Resource File System is not present}.
	 * On the JVM the {@code resource} scheme has no provider, so this is a no-op there.
	 */
	private void ensureNativeImageResourceFileSystem() {
		try {
			FileSystems.newFileSystem(URI.create("resource:/"), Collections.emptyMap());
			log.debug("Opened native-image resource file system for Playwright driver extraction");
		}
		catch (FileSystemAlreadyExistsException ex) {
			// Already open — nothing to do.
		}
		catch (IOException | RuntimeException ex) {
			// Running on the JVM (no "resource" provider) or not needed.
			log.debug("Skipping native-image resource file system init: {}", ex.getMessage());
		}
	}

}
