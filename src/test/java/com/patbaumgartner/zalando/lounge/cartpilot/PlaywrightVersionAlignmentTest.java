package com.patbaumgartner.zalando.lounge.cartpilot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

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
 * client (or the reverse) fails CI until the other side catches up.
 */
@DisplayName("Playwright / Patchright version alignment")
class PlaywrightVersionAlignmentTest {

	@Test
	@DisplayName("the Patchright sidecar speaks the same protocol version as the Playwright client")
	void patchrightMatchesPlaywrightClient() throws IOException {
		var clientVersion = BrowserVersions.playwrightClientVersion();
		var patchrightVersion = BrowserVersions.patchrightSidecarVersion();

		assertThat(BrowserVersions.protocolVersion(patchrightVersion))
			.as(BrowserVersions.mismatchMessage(patchrightVersion, clientVersion))
			.isEqualTo(BrowserVersions.protocolVersion(clientVersion));
	}

}
