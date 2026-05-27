package io.jaylee.amr.demo.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import redis.clients.authentication.core.TokenAuthConfig;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the {@code azure} Spring profile swaps {@link TokenAuthConfig} from the
 * DefaultAzureCredential path (no profile) to the Managed Identity path (profile active),
 * and that misconfiguration is caught at context-startup time.
 */
class TokenAuthConfigProfileSelectionTest {

	private static final String[] MIN_PROPS = { "app.redis.health-strategy=ping",
			"app.redis.initialization-policy=ONE_AVAILABLE", "app.redis.failback-supported=true",
			"app.redis.failback-check-interval=10s", "app.redis.grace-period=5s",
			"app.redis.delay-between-failover-attempts=2s", "app.redis.circuit-breaker.metrics-window-size=5s",
			"app.redis.circuit-breaker.minimum-number-of-failures=3",
			"app.redis.circuit-breaker.failure-rate-threshold=50.0", "app.redis.health-check.interval=1s",
			"app.redis.health-check.timeout=1s", "app.redis.health-check.num-probes=1",
			"app.redis.health-check.delay-between-probes=100ms", "app.redis.health-check.policy=ALL_SUCCESS",
			"app.redis.client.connect-timeout=2s", "app.redis.client.socket-timeout=5s",
			"app.redis.endpoints[0].name=primary", "app.redis.endpoints[0].host=primary.example.com",
			"app.redis.endpoints[0].port=10000", "app.redis.endpoints[0].weight=1.0",
			"app.redis.endpoints[1].name=secondary", "app.redis.endpoints[1].host=secondary.example.com",
			"app.redis.endpoints[1].port=10000", "app.redis.endpoints[1].weight=0.5", };

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withUserConfiguration(TestConfig.class, EntraAuthConfig.class)
		.withPropertyValues(MIN_PROPS);

	@Test
	void defaultProfileExposesDefaultAzureCredentialTokenAuthConfig() {
		runner.run(ctx -> {
			assertThat(ctx).hasNotFailed();
			assertThat(ctx).hasSingleBean(TokenAuthConfig.class);
		});
	}

	@Test
	void azureProfileExposesManagedIdentityTokenAuthConfigForSystemAssigned() {
		runner.withPropertyValues("spring.profiles.active=azure", "app.redis.entra.identity.type=SYSTEM").run(ctx -> {
			assertThat(ctx).hasNotFailed();
			assertThat(ctx).hasSingleBean(TokenAuthConfig.class);
		});
	}

	@Test
	void azureProfileExposesManagedIdentityTokenAuthConfigForUserAssignedClientId() {
		runner
			.withPropertyValues("spring.profiles.active=azure", "app.redis.entra.identity.type=USER_ASSIGNED_CLIENT_ID",
					"app.redis.entra.identity.id=11111111-2222-3333-4444-555555555555")
			.run(ctx -> {
				assertThat(ctx).hasNotFailed();
				assertThat(ctx).hasSingleBean(TokenAuthConfig.class);
			});
	}

	@Test
	void azureProfileDefaultsToSystemAssignedWhenIdentityBlockMissing() {
		runner.withPropertyValues("spring.profiles.active=azure").run(ctx -> {
			assertThat(ctx).hasNotFailed();
			assertThat(ctx).hasSingleBean(TokenAuthConfig.class);
		});
	}

	@Test
	void azureProfileFailsFastWhenSystemIdentityHasId() {
		runner
			.withPropertyValues("spring.profiles.active=azure", "app.redis.entra.identity.type=SYSTEM",
					"app.redis.entra.identity.id=should-not-be-here")
			.run(ctx -> {
				assertThat(ctx).hasFailed();
				assertThat(ctx).getFailure().rootCause().hasMessageContaining("must be blank when type=SYSTEM");
			});
	}

	@Test
	void azureProfileFailsFastWhenUserAssignedIdentityMissingId() {
		runner
			.withPropertyValues("spring.profiles.active=azure", "app.redis.entra.identity.type=USER_ASSIGNED_CLIENT_ID")
			.run(ctx -> {
				assertThat(ctx).hasFailed();
				assertThat(ctx).getFailure()
					.rootCause()
					.hasMessageContaining("is required when type=USER_ASSIGNED_CLIENT_ID");
			});
	}

	@EnableConfigurationProperties(AmrRedisProperties.class)
	static class TestConfig {

	}

}
