package io.jaylee.amr.demo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

@ConfigurationProperties(prefix = "app.redis")
public record AmrRedisProperties(

		HealthStrategy healthStrategy,

		InitializationPolicyOption initializationPolicy,

		boolean failbackSupported,

		Duration failbackCheckInterval,

		Duration gracePeriod,

		Duration delayBetweenFailoverAttempts,

		CircuitBreaker circuitBreaker,

		HealthCheck healthCheck,

		LagAware lagAware,

		Entra entra,

		ClientTimeouts client,

		List<Endpoint> endpoints) {

	public enum HealthStrategy {

		PING, LAG_AWARE

	}

	public enum InitializationPolicyOption {

		ALL_AVAILABLE, MAJORITY_AVAILABLE, ONE_AVAILABLE

	}

	public record Endpoint(String name, String host, int port, float weight,
			/** Only used by LagAwareStrategy; ignored otherwise. */
			String restApiUri) {
	}

	public record CircuitBreaker(Duration metricsWindowSize, int minimumNumberOfFailures, float failureRateThreshold) {
	}

	public record HealthCheck(Duration interval, Duration timeout, int numProbes, Duration delayBetweenProbes,
			ProbingPolicyOption policy) {
		public enum ProbingPolicyOption {

			ALL_SUCCESS, ANY_SUCCESS, MAJORITY_SUCCESS

		}
	}

	public record LagAware(boolean extendedCheckEnabled, Duration availabilityLagTolerance) {
	}

	public record Entra(
			/**
			 * Comma-separated AAD scopes; defaults to https://redis.azure.com/.default.
			 */
			String tokenScope, float expirationRefreshRatio, Duration lowerRefreshBound, Duration tokenRequestTimeout,
			/**
			 * Only consulted when the {@code azure} Spring profile is active. Controls
			 * which managed identity {@code EntraAuthConfig} acquires Entra tokens with.
			 * Ignored entirely under the default profile (which uses
			 * {@code DefaultAzureCredential}).
			 */
			Identity identity) {
	}

	/**
	 * Managed Identity selector for the {@code azure} Spring profile.
	 *
	 * <p>
	 * {@link IdentityType#SYSTEM} uses the workload's system-assigned identity and must
	 * leave {@link #id()} blank. {@code USER_ASSIGNED_*} variants require a non-blank
	 * {@link #id()} matching the chosen discriminator (client ID, object ID, or Azure
	 * resource ID of the user-assigned identity).
	 */
	public record Identity(IdentityType type, String id) {

		public Identity {
			if (type == null) {
				throw new IllegalArgumentException("app.redis.entra.identity.type must be set when the identity "
						+ "block is present (SYSTEM | USER_ASSIGNED_CLIENT_ID | USER_ASSIGNED_OBJECT_ID | "
						+ "USER_ASSIGNED_RESOURCE_ID)");
			}
			boolean idPresent = id != null && !id.isBlank();
			if (type == IdentityType.SYSTEM && idPresent) {
				throw new IllegalArgumentException(
						"app.redis.entra.identity.id must be blank when type=SYSTEM (got '" + id + "')");
			}
			if (type != IdentityType.SYSTEM && !idPresent) {
				throw new IllegalArgumentException("app.redis.entra.identity.id is required when type=" + type
						+ " (the user-assigned " + "managed identity's client/object/resource ID)");
			}
		}

		public enum IdentityType {

			SYSTEM, USER_ASSIGNED_CLIENT_ID, USER_ASSIGNED_OBJECT_ID, USER_ASSIGNED_RESOURCE_ID

		}
	}

	/**
	 * Per-connection timeouts applied to every {@code JedisClientConfig} built by
	 * {@link RedisMultiDbConfig} — both the {@code MultiDbClient} routed connections and
	 * the pinned per-endpoint connections.
	 *
	 * <p>
	 * Defaults are demo-tuned: socket-timeout matches the health-check timeout (5s) so a
	 * cold TLS + Entra-AUTH handshake on the first command after startup or failback does
	 * not spuriously time out. connect-timeout is tighter (2s) because a TCP connect that
	 * cannot complete in 2s indicates the region is unreachable, not a slow handshake.
	 *
	 * <p>
	 * Jedis defaults (when this record is absent) are 2000ms for both via
	 * {@code Protocol.DEFAULT_TIMEOUT}.
	 */
	public record ClientTimeouts(Duration connectTimeout, Duration socketTimeout) {
		public ClientTimeouts {
			validate("connect-timeout", connectTimeout);
			validate("socket-timeout", socketTimeout);
		}

		private static void validate(String name, Duration value) {
			if (value == null || value.isZero() || value.isNegative()) {
				throw new IllegalArgumentException("app.redis.client." + name + " must be a positive duration");
			}
			long millis = value.toMillis();
			if (millis < 1 || millis > Integer.MAX_VALUE) {
				throw new IllegalArgumentException("app.redis.client." + name
						+ " must be between 1ms and Integer.MAX_VALUE ms (got " + value + ")");
			}
		}
	}
}
