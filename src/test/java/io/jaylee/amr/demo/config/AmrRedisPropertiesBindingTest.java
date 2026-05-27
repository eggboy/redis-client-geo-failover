package io.jaylee.amr.demo.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = AmrRedisPropertiesBindingTest.TestConfig.class,
		webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = { "app.redis.health-strategy=ping", "app.redis.initialization-policy=ONE_AVAILABLE",
		"app.redis.failback-supported=true", "app.redis.failback-check-interval=10s", "app.redis.grace-period=5s",
		"app.redis.delay-between-failover-attempts=2s", "app.redis.circuit-breaker.metrics-window-size=5s",
		"app.redis.circuit-breaker.minimum-number-of-failures=3",
		"app.redis.circuit-breaker.failure-rate-threshold=50.0", "app.redis.health-check.interval=1s",
		"app.redis.health-check.timeout=1s", "app.redis.health-check.num-probes=1",
		"app.redis.health-check.delay-between-probes=100ms", "app.redis.health-check.policy=ALL_SUCCESS",
		"app.redis.client.connect-timeout=2s", "app.redis.client.socket-timeout=5s",
		"app.redis.endpoints[0].name=primary", "app.redis.endpoints[0].host=primary.example.com",
		"app.redis.endpoints[0].port=10000", "app.redis.endpoints[0].weight=1.0",
		"app.redis.endpoints[1].name=secondary", "app.redis.endpoints[1].host=secondary.example.com",
		"app.redis.endpoints[1].port=10000", "app.redis.endpoints[1].weight=0.5" })
class AmrRedisPropertiesBindingTest {

	@org.springframework.boot.SpringBootConfiguration
	@EnableConfigurationProperties(AmrRedisProperties.class)
	static class TestConfig {

	}

	@org.springframework.beans.factory.annotation.Autowired
	private AmrRedisProperties properties;

	@Test
	void bindsTopLevelOptions() {
		assertThat(properties.healthStrategy()).isEqualTo(AmrRedisProperties.HealthStrategy.PING);
		assertThat(properties.initializationPolicy())
			.isEqualTo(AmrRedisProperties.InitializationPolicyOption.ONE_AVAILABLE);
		assertThat(properties.failbackSupported()).isTrue();
		assertThat(properties.failbackCheckInterval()).hasSeconds(10);
		assertThat(properties.gracePeriod()).hasSeconds(5);
		assertThat(properties.delayBetweenFailoverAttempts()).hasSeconds(2);
	}

	@Test
	void bindsCircuitBreaker() {
		var cb = properties.circuitBreaker();
		assertThat(cb.metricsWindowSize()).hasSeconds(5);
		assertThat(cb.minimumNumberOfFailures()).isEqualTo(3);
		assertThat(cb.failureRateThreshold()).isEqualTo(50.0f);
	}

	@Test
	void bindsHealthCheck() {
		var hc = properties.healthCheck();
		assertThat(hc.interval()).hasMillis(1000);
		assertThat(hc.timeout()).hasMillis(1000);
		assertThat(hc.numProbes()).isEqualTo(1);
		assertThat(hc.delayBetweenProbes()).hasMillis(100);
		assertThat(hc.policy()).isEqualTo(AmrRedisProperties.HealthCheck.ProbingPolicyOption.ALL_SUCCESS);
	}

	@Test
	void bindsTwoEndpointsInOrder() {
		assertThat(properties.endpoints()).hasSize(2);
		var primary = properties.endpoints().get(0);
		var secondary = properties.endpoints().get(1);
		assertThat(primary.name()).isEqualTo("primary");
		assertThat(primary.host()).isEqualTo("primary.example.com");
		assertThat(primary.port()).isEqualTo(10000);
		assertThat(primary.weight()).isEqualTo(1.0f);
		assertThat(secondary.name()).isEqualTo("secondary");
		assertThat(secondary.weight()).isEqualTo(0.5f);
	}

	@Test
	void bindsClientTimeouts() {
		var client = properties.client();
		assertThat(client).isNotNull();
		assertThat(client.connectTimeout()).hasSeconds(2);
		assertThat(client.socketTimeout()).hasSeconds(5);
	}

}
