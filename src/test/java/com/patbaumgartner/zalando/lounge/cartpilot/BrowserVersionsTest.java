package com.patbaumgartner.zalando.lounge.cartpilot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Browser version parsing")
class BrowserVersionsTest {

	@Test
	@DisplayName("protocol version keeps major and minor only")
	void protocolVersionKeepsMajorAndMinor() {
		assertThat(BrowserVersions.protocolVersion("1.65.0")).isEqualTo("1.65");
		assertThat(BrowserVersions.protocolVersion("1.64.3")).isEqualTo("1.64");
		assertThat(BrowserVersions.protocolVersion("2")).isEqualTo("2");
	}

	@Test
	@DisplayName("mismatch message names the lagging pin and the remediation")
	void mismatchMessageNamesRemediation() {
		assertThat(BrowserVersions.mismatchMessage("1.64.3", "1.65.0")).contains("patchright 1.64.3")
			.contains("com.microsoft.playwright 1.65.0")
			.contains("patchright/package.json")
			.contains("patchright/package-lock.json");
	}

}
