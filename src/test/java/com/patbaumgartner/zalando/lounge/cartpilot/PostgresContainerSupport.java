package com.patbaumgartner.zalando.lounge.cartpilot;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base class for integration tests that need a real PostgreSQL. Each subclass gets its
 * own container, started before and torn down after that subclass's tests only.
 * <p>
 * Do not give a `@SpringBootTest` class a real datasource any other way:
 * {@code @DynamicPropertySource}-contributed properties are not part of Spring's test
 * context cache key, so a class with no Testcontainers setup of its own can silently be
 * handed another class's already-stopped container instead of failing fast — the failed
 * reconnect attempts then retry for as long as the CI job's timeout allows before anyone
 * notices.
 */
@Testcontainers(disabledWithoutDocker = true)
public abstract class PostgresContainerSupport {

	@Container
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

	@DynamicPropertySource
	static void overrideDataSource(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", postgres::getJdbcUrl);
		registry.add("spring.datasource.username", postgres::getUsername);
		registry.add("spring.datasource.password", postgres::getPassword);
		registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
	}

}
