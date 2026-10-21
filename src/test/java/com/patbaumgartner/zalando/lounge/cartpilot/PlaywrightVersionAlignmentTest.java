package com.patbaumgartner.zalando.lounge.cartpilot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the wire-protocol contract between the Playwright Java client
 * ({@code com.microsoft.playwright:playwright}) and the Patchright sidecar
 * ({@code patchright} in {@code patchright/package.json}).
 *
 * <p>
 * The two speak a version-locked protocol: the server rejects a client whose version
 * differs with HTTP 428 <em>before a page can be opened</em>, which takes the whole scan
 * down with "Browser unavailable" and leaves the group with no results. Dependabot bumps
 * the two on independent schedules, so a patchright bump that runs ahead of the Java
 * client (or the reverse) silently breaks every run until this test is noticed.
 */
@DisplayName("Playwright / Patchright version alignment")
class PlaywrightVersionAlignmentTest {

	private static final Pattern PLAYWRIGHT_VERSION = Pattern
		.compile("<playwright\\.version>([^<]+)</playwright\\.version>");

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	@DisplayName("the Patchright sidecar speaks the same protocol version as the Playwright client")
	void patchrightMatchesPlaywrightClient() throws IOException {
		var clientVersion = playwrightClientVersion();
		var patchrightVersion = patchrightSidecarVersion();

		assertThat(protocolVersion(patchrightVersion))
			.as("patchright %s and com.microsoft.playwright %s must share the same major.minor — a mismatch "
					+ "is rejected with HTTP 428 and no browser page can be opened", patchrightVersion, clientVersion)
			.isEqualTo(protocolVersion(clientVersion));
	}

	private String playwrightClientVersion() throws IOException {
		var matcher = PLAYWRIGHT_VERSION.matcher(Files.readString(projectRoot().resolve("pom.xml")));
		assertThat(matcher.find()).as("pom.xml must declare <playwright.version>").isTrue();
		return matcher.group(1).trim();
	}

	private String patchrightSidecarVersion() throws IOException {
		JsonNode packageJson = objectMapper
			.readTree(Files.readString(projectRoot().resolve("patchright/package.json")));
		var version = packageJson.path("dependencies").path("patchright").asString("");
		assertThat(version).as("patchright/package.json must pin a patchright version").isNotBlank();
		return version;
	}

	/**
	 * The protocol version Playwright reports and checks is {@code major.minor} (e.g. a
	 * 1.64 server rejects a 1.63 client), so that is what has to stay aligned. A patch
	 * release on either side keeps the same protocol and is allowed to differ.
	 */
	private static String protocolVersion(String version) {
		var parts = version.trim().split("\\.");
		return parts.length >= 2 ? parts[0] + "." + parts[1] : version;
	}

	/**
	 * Surefire runs from the module root, but walking up keeps the test working from any
	 * working directory (IDE, forked JVM, nested launcher).
	 */
	private static Path projectRoot() {
		var dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
		while (dir != null) {
			if (Files.exists(dir.resolve("pom.xml")) && Files.exists(dir.resolve("patchright/package.json"))) {
				return dir;
			}
			dir = dir.getParent();
		}
		throw new IllegalStateException("Could not locate the project root (pom.xml + patchright/package.json)");
	}

}
