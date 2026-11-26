package com.patbaumgartner.zalando.lounge.cartpilot;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reads the two version pins that must agree for the Playwright wire protocol.
 *
 * <p>
 * Keeping the parsing in one place means Dependabot bumps only ever touch the two
 * declaration files — {@code pom.xml} and {@code patchright/package.json}. There is no
 * third manifest to keep in sync.
 */
final class BrowserVersions {

	private static final Pattern PLAYWRIGHT_VERSION = Pattern
		.compile("<playwright\\.version>([^<]+)</playwright\\.version>");

	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

	private BrowserVersions() {
	}

	static String playwrightClientVersion() throws IOException {
		var matcher = PLAYWRIGHT_VERSION.matcher(Files.readString(projectRoot().resolve("pom.xml")));
		assertThat(matcher.find()).as("pom.xml must declare <playwright.version>").isTrue();
		return matcher.group(1).trim();
	}

	static String patchrightSidecarVersion() throws IOException {
		JsonNode packageJson = OBJECT_MAPPER
			.readTree(Files.readString(projectRoot().resolve("patchright/package.json")));
		var version = packageJson.path("dependencies").path("patchright").asString("");
		assertThat(version).as("patchright/package.json must pin a patchright version").isNotBlank();
		return version;
	}

	/**
	 * The protocol version is {@code major.minor}; patch releases of either side stay
	 * wire-compatible and are allowed to differ.
	 */
	static String protocolVersion(String version) {
		var parts = version.trim().split("\\.");
		return parts.length >= 2 ? parts[0] + "." + parts[1] : version;
	}

	static String mismatchMessage(String patchrightVersion, String clientVersion) {
		return "patchright " + patchrightVersion + " and com.microsoft.playwright " + clientVersion
				+ " must share the same major.minor — a mismatch is rejected with HTTP 428. "
				+ "Leave the Dependabot PR open until a matching patchright release exists, then update "
				+ "patchright/package.json and patchright/package-lock.json in a normal PR.";
	}

	/**
	 * Surefire runs from the module root, but walking up keeps this working from any
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
