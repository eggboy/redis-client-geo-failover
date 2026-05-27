package io.jaylee.amr.demo.config;

import io.jaylee.amr.demo.service.PinnedConnections;
import io.jaylee.amr.demo.service.SwitchEventLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import redis.clients.authentication.core.TokenAuthConfig;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisClientConfig;
import redis.clients.jedis.MultiDbClient;
import redis.clients.jedis.MultiDbConfig;
import redis.clients.jedis.MultiDbConfig.CircuitBreakerConfig;
import redis.clients.jedis.MultiDbConfig.DatabaseConfig;
import redis.clients.jedis.MultiDbConfig.StrategySupplier;
import redis.clients.jedis.RedisCredentials;
import redis.clients.jedis.SslOptions;
import redis.clients.jedis.authentication.AuthXManager;
import redis.clients.jedis.mcf.HealthCheckStrategy;
import redis.clients.jedis.mcf.InitializationPolicy;
import redis.clients.jedis.mcf.LagAwareStrategy;
import redis.clients.jedis.mcf.PingStrategy;
import redis.clients.jedis.mcf.ProbingPolicy;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

import static io.jaylee.amr.demo.config.AmrRedisProperties.HealthStrategy.LAG_AWARE;

@Configuration
public class RedisMultiDbConfig {

	private static final Logger log = LoggerFactory.getLogger(RedisMultiDbConfig.class);

	private static final int PING_DEFAULT_INTERVAL_MS = 1000;

	private static final int PING_DEFAULT_TIMEOUT_MS = 1000;

	private static final int PING_DEFAULT_NUM_PROBES = 3;

	private static final int PING_DEFAULT_DELAY_BETWEEN_PROBES_MS = 100;

	private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(2);

	private static final Duration DEFAULT_SOCKET_TIMEOUT = Duration.ofSeconds(5);

	/**
	 * Wraps the profile-supplied {@link TokenAuthConfig} in a started
	 * {@link AuthXManager}. The credential source itself is chosen by
	 * {@link EntraAuthConfig}, which exposes exactly one {@code TokenAuthConfig} bean
	 * depending on the active Spring profile.
	 */
	@Bean(destroyMethod = "stop")
	public AuthXManager authXManager(TokenAuthConfig tokenAuthConfig) {
		log.info("Starting AuthXManager (initial token will be acquired synchronously)");
		AuthXManager manager = new AuthXManager(tokenAuthConfig);
		manager.start();
		return manager;
	}

	@Bean(destroyMethod = "close")
	public MultiDbClient multiDbClient(AmrRedisProperties props, AuthXManager authXManager,
			SwitchEventLogger switchEventLogger) {

		JedisClientConfig clientConfig = buildJedisClientConfig(props, authXManager);

		MultiDbConfig.Builder configBuilder = MultiDbConfig.builder();
		for (AmrRedisProperties.Endpoint endpoint : props.endpoints()) {
			configBuilder.database(buildDatabaseConfig(endpoint, props, clientConfig, authXManager));
		}

		configBuilder.failureDetector(buildCircuitBreaker(props.circuitBreaker()));

		configBuilder.failbackSupported(props.failbackSupported());
		if (props.failbackCheckInterval() != null) {
			configBuilder.failbackCheckInterval(props.failbackCheckInterval().toMillis());
		}
		if (props.gracePeriod() != null) {
			configBuilder.gracePeriod(props.gracePeriod().toMillis());
		}
		if (props.delayBetweenFailoverAttempts() != null) {
			configBuilder.delayInBetweenFailoverAttempts((int) props.delayBetweenFailoverAttempts().toMillis());
		}
		configBuilder.initializationPolicy(resolveInitPolicy(props.initializationPolicy()));
		configBuilder.commandRetry(MultiDbConfig.RetryConfig.builder().maxAttempts(3).build());

		MultiDbConfig multiDbConfig = configBuilder.build();

		log.info(
				"Creating MultiDbClient with {} endpoints (initPolicy={}, failback={}, failbackInterval={}, gracePeriod={})",
				props.endpoints().size(), props.initializationPolicy(), props.failbackSupported(),
				props.failbackCheckInterval(), props.gracePeriod());

		return MultiDbClient.builder()
			.multiDbConfig(multiDbConfig)
			.databaseSwitchListener(switchEventLogger::handle)
			.build();
	}

	/**
	 * Per-endpoint pinned connections, one per configured
	 * {@link AmrRedisProperties.Endpoint}, bypassing {@code MultiDbClient}'s routing
	 * entirely. <b>TEST USE ONLY</b> — backs {@code GET
	 * /admin/pinned/{endpoint}/sessions/{sid}} for Scenario 1. See ADR-0002.
	 *
	 * <p>
	 * Shares the same {@link AuthXManager} as the {@code MultiDbClient} so Entra token
	 * refresh applies uniformly. Each endpoint gets its own
	 * {@link redis.clients.jedis.RedisClient}.
	 */
	@Bean(destroyMethod = "close")
	public PinnedConnections pinnedConnections(AmrRedisProperties props, AuthXManager authXManager) {
		JedisClientConfig clientConfig = buildJedisClientConfig(props, authXManager);
		Map<String, redis.clients.jedis.RedisClient> clients = new LinkedHashMap<>();
		for (AmrRedisProperties.Endpoint endpoint : props.endpoints()) {
			try {
				redis.clients.jedis.RedisClient client = redis.clients.jedis.RedisClient.builder()
					.hostAndPort(new HostAndPort(endpoint.host(), endpoint.port()))
					.clientConfig(clientConfig)
					.build();
				clients.put(endpoint.name(), client);
				log.info("Pinned connection ready for endpoint '{}' ({}:{})", endpoint.name(), endpoint.host(),
						endpoint.port());
			}
			catch (RuntimeException e) {
				log.warn(
						"Failed to open pinned connection for endpoint '{}' ({}:{}): {}. "
								+ "Scenarios that depend on it will fail with 503.",
						endpoint.name(), endpoint.host(), endpoint.port(), e.toString());
			}
		}
		return new PinnedConnections(clients);
	}

	private DatabaseConfig buildDatabaseConfig(AmrRedisProperties.Endpoint endpoint, AmrRedisProperties props,
			JedisClientConfig clientConfig, AuthXManager authXManager) {
		HostAndPort hap = new HostAndPort(endpoint.host(), endpoint.port());
		DatabaseConfig.Builder builder = DatabaseConfig.builder(hap, clientConfig)
			.weight(endpoint.weight())
			.healthCheckStrategySupplier(buildHealthCheckSupplier(endpoint, props, authXManager));
		return builder.build();
	}

	private JedisClientConfig buildJedisClientConfig(AmrRedisProperties props, AuthXManager authXManager) {
		AmrRedisProperties.ClientTimeouts timeouts = props.client();
		Duration connect = timeouts != null && timeouts.connectTimeout() != null ? timeouts.connectTimeout()
				: DEFAULT_CONNECT_TIMEOUT;
		Duration socket = timeouts != null && timeouts.socketTimeout() != null ? timeouts.socketTimeout()
				: DEFAULT_SOCKET_TIMEOUT;
		log.info("Jedis client timeouts: connect={} socket={}", connect, socket);
		return DefaultJedisClientConfig.builder()
			.authXManager(authXManager)
			.ssl(true)
			.connectionTimeoutMillis((int) connect.toMillis())
			.socketTimeoutMillis((int) socket.toMillis())
			.build();
	}

	private MultiDbConfig.CircuitBreakerConfig buildCircuitBreaker(AmrRedisProperties.CircuitBreaker cb) {
		var builder = MultiDbConfig.CircuitBreakerConfig.builder();
		if (cb == null) {
			return builder.build();
		}
		if (cb.metricsWindowSize() != null) {
			builder.slidingWindowSize((int) Math.max(1L, cb.metricsWindowSize().toSeconds()));
		}
		if (cb.minimumNumberOfFailures() > 0) {
			builder.minNumOfFailures(cb.minimumNumberOfFailures());
		}
		if (cb.failureRateThreshold() > 0f) {
			builder.failureRateThreshold(cb.failureRateThreshold());
		}
		return builder.build();
	}

	private InitializationPolicy resolveInitPolicy(AmrRedisProperties.InitializationPolicyOption opt) {
		if (opt == null) {
			return InitializationPolicy.BuiltIn.ONE_AVAILABLE;
		}
		return switch (opt) {
			case ALL_AVAILABLE -> InitializationPolicy.BuiltIn.ALL_AVAILABLE;
			case MAJORITY_AVAILABLE -> InitializationPolicy.BuiltIn.MAJORITY_AVAILABLE;
			case ONE_AVAILABLE -> InitializationPolicy.BuiltIn.ONE_AVAILABLE;
		};
	}

	private StrategySupplier buildHealthCheckSupplier(AmrRedisProperties.Endpoint endpoint, AmrRedisProperties props,
			AuthXManager authXManager) {
		AmrRedisProperties.HealthCheck hc = props.healthCheck();
		if (props.healthStrategy() == LAG_AWARE) {
			if (endpoint.restApiUri() == null || endpoint.restApiUri().isBlank()) {
				throw new IllegalStateException(
						"health-strategy=LAG_AWARE requires app.redis.endpoints[].rest-api-uri for endpoint '"
								+ endpoint.name() + "'. AMR does not expose the Redis Enterprise REST API by default; "
								+ "switch to health-strategy=PING or configure the cluster REST endpoint.");
			}
			return buildLagAwareSupplier(endpoint, hc, props.lagAware(), authXManager);
		}
		return buildPingSupplier(hc);
	}

	private StrategySupplier buildPingSupplier(AmrRedisProperties.HealthCheck hc) {
		if (hc == null) {
			return PingStrategy.DEFAULT;
		}
		HealthCheckStrategy.Config config = new HealthCheckStrategy.Config(
				hc.interval() != null ? (int) hc.interval().toMillis() : PING_DEFAULT_INTERVAL_MS,
				hc.timeout() != null ? (int) hc.timeout().toMillis() : PING_DEFAULT_TIMEOUT_MS,
				hc.numProbes() > 0 ? hc.numProbes() : PING_DEFAULT_NUM_PROBES,
				hc.delayBetweenProbes() != null ? (int) hc.delayBetweenProbes().toMillis()
						: PING_DEFAULT_DELAY_BETWEEN_PROBES_MS,
				hc.policy() != null ? resolveProbingPolicy(hc.policy()) : ProbingPolicy.BuiltIn.ALL_SUCCESS);
		return (hap, cfg) -> new PingStrategy(hap, cfg, config);
	}

	private StrategySupplier buildLagAwareSupplier(AmrRedisProperties.Endpoint endpoint,
			AmrRedisProperties.HealthCheck hc, AmrRedisProperties.LagAware lag, AuthXManager authXManager) {
		URI restUri = URI.create(endpoint.restApiUri());
		HostAndPort restEndpoint = new HostAndPort(restUri.getHost(), restUri.getPort());
		Supplier<RedisCredentials> credSupplier = authXManager::get;

		LagAwareStrategy.Config.ConfigBuilder builder = LagAwareStrategy.Config.builder(restEndpoint, credSupplier)
			.sslOptions(SslOptions.defaults());

		if (lag != null) {
			builder.extendedCheckEnabled(lag.extendedCheckEnabled());
			if (lag.availabilityLagTolerance() != null) {
				builder.availabilityLagTolerance(lag.availabilityLagTolerance());
			}
		}
		if (hc != null) {
			if (hc.interval() != null) {
				builder.interval((int) hc.interval().toMillis());
			}
			if (hc.timeout() != null) {
				builder.timeout((int) hc.timeout().toMillis());
			}
		}
		LagAwareStrategy.Config config = builder.build();
		return (hap, cfg) -> new LagAwareStrategy(config);
	}

	private ProbingPolicy resolveProbingPolicy(AmrRedisProperties.HealthCheck.ProbingPolicyOption opt) {
		return switch (opt) {
			case ALL_SUCCESS -> ProbingPolicy.BuiltIn.ALL_SUCCESS;
			case ANY_SUCCESS -> ProbingPolicy.BuiltIn.ANY_SUCCESS;
			case MAJORITY_SUCCESS -> ProbingPolicy.BuiltIn.MAJORITY_SUCCESS;
		};
	}

}
