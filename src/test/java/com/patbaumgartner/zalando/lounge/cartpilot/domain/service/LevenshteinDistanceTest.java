package com.patbaumgartner.zalando.lounge.cartpilot.domain.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("LevenshteinDistance")
class LevenshteinDistanceTest {

	@Test
	@DisplayName("returns 0 for identical strings")
	void identicalStrings() {
		assertThat(LevenshteinDistance.compute("mammut", "mammut")).isZero();
	}

	@Test
	@DisplayName("returns string length when other is empty")
	void oneEmpty() {
		assertThat(LevenshteinDistance.compute("abc", "")).isEqualTo(3);
		assertThat(LevenshteinDistance.compute("", "abc")).isEqualTo(3);
	}

	@Test
	@DisplayName("returns 0 for two empty strings")
	void bothEmpty() {
		assertThat(LevenshteinDistance.compute("", "")).isZero();
	}

	@ParameterizedTest(name = "''{0}'' vs ''{1}'' = {2}")
	@DisplayName("computes correct edit distances")
	@CsvSource({ "mammut,   mammut,    0", "mammut,   mamut,     1", "arcteryx, arcterxy,  2", "salomon,  salomn,    1",
			"cat,      dog,       3", "kitten,   sitting,   3" })
	void computesEditDistance(String a, String b, int expected) {
		assertThat(LevenshteinDistance.compute(a.trim(), b.trim())).isEqualTo(expected);
	}

	@Test
	@DisplayName("is symmetric")
	void isSymmetric() {
		assertThat(LevenshteinDistance.compute("abc", "xyz")).isEqualTo(LevenshteinDistance.compute("xyz", "abc"));
	}

}
