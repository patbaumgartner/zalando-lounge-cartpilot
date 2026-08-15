package com.patbaumgartner.zalando.lounge.cartpilot;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base class for integration tests that need a real PostgreSQL: the "singleton container"
 * pattern from the Testcontainers docs. One container is started once, manually, for the
 * whole JVM/test run and never explicitly stopped — Ryuk cleans it up on JVM exit.
 * <p>
 * A {@code static @Container} field declared here instead would belong to this class
 * alone, not to each subclass: every subclass extending it shares the exact same field,
 * so the {@code @Testcontainers} extension stops it once the first subclass's tests
 * finish, and every later subclass is left with a dead container. HikariCP then retries
 * the closed connection roughly once a minute until the CI job's timeout kills it, even
 * though every test already passed. Managing the container manually — start it once, let
 * it outlive every class — sidesteps that per-class stop/start lifecycle entirely.
 */
public abstract class PostgresContainerSupport {

	static PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

	static {
		POSTGRES.start();
	}

	@DynamicPropertySource
	static void overrideDataSource(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
		registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
	}

}
