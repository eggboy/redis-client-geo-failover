package io.jaylee.amr.demo.config;

import com.azure.identity.DefaultAzureCredential;
import com.azure.identity.DefaultAzureCredentialBuilder;
import io.jaylee.amr.demo.config.AmrRedisProperties.Identity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import redis.clients.authentication.core.TokenAuthConfig;
import redis.clients.authentication.entraid.AzureTokenAuthConfigBuilder;
import redis.clients.authentication.entraid.EntraIDTokenAuthConfigBuilder;
import redis.clients.authentication.entraid.ManagedIdentityInfo.UserManagedIdentityType;

import java.time.Duration;
import java.util.Set;

/**
 * Builds the Entra ID {@link TokenAuthConfig} bean consumed by
 * {@link RedisMultiDbConfig#authXManager}. Exactly one of the two factory methods is
 * active at runtime, selected by the {@code azure} Spring profile.
 */
@Configuration
public class EntraAuthConfig {

	private static final Logger log = LoggerFactory.getLogger(EntraAuthConfig.class);

	private static final String DEFAULT_AMR_SCOPE = "https://redis.azure.com/.default";

	private static final float DEFAULT_TOKEN_REFRESH_RATIO = 0.7f;

	private static final int DEFAULT_TOKEN_LOWER_REFRESH_MS = (int) Duration.ofMinutes(3).toMillis();

	private static final int DEFAULT_TOKEN_REQUEST_TIMEOUT_MS = (int) Duration.ofSeconds(10).toMillis();

	@Bean
	@Profile("!azure")
	public TokenAuthConfig defaultCredentialTokenAuth(AmrRedisProperties props) {
		AmrRedisProperties.Entra entra = props.entra();
		Set<String> scopes = scopes(entra);
		float refreshRatio = refreshRatio(entra);
		int lowerRefreshMs = lowerRefreshBoundMs(entra);
		int tokenTimeoutMs = tokenRequestTimeoutMs(entra);

		DefaultAzureCredential credential = new DefaultAzureCredentialBuilder().build();

		log.info(
				"Building TokenAuthConfig from DefaultAzureCredential chain "
						+ "(scopes={}, refreshRatio={}, lowerRefreshMs={}). "
						+ "Activate the 'azure' Spring profile in production to use Managed Identity explicitly.",
				scopes, refreshRatio, lowerRefreshMs);

		return new AzureTokenAuthConfigBuilder().defaultAzureCredential(credential)
			.scopes(scopes)
			.tokenRequestExecTimeoutInMs(tokenTimeoutMs)
			.expirationRefreshRatio(refreshRatio)
			.lowerRefreshBoundMillis(lowerRefreshMs)
			.build();
	}

	@Bean
	@Profile("azure")
	public TokenAuthConfig managedIdentityTokenAuth(AmrRedisProperties props) {
		AmrRedisProperties.Entra entra = props.entra();
		Identity identity = entra != null ? entra.identity() : null;
		if (identity == null) {
			identity = new Identity(Identity.IdentityType.SYSTEM, null);
			log.info("app.redis.entra.identity not set under 'azure' profile; "
					+ "defaulting to system-assigned Managed Identity");
		}

		Set<String> scopes = scopes(entra);
		float refreshRatio = refreshRatio(entra);
		int lowerRefreshMs = lowerRefreshBoundMs(entra);
		int tokenTimeoutMs = tokenRequestTimeoutMs(entra);

		EntraIDTokenAuthConfigBuilder builder = EntraIDTokenAuthConfigBuilder.builder()
			.scopes(scopes)
			.tokenRequestExecTimeoutInMs(tokenTimeoutMs)
			.expirationRefreshRatio(refreshRatio)
			.lowerRefreshBoundMillis(lowerRefreshMs);

		switch (identity.type()) {
			case SYSTEM -> {
				builder.systemAssignedManagedIdentity();
				log.info(
						"Building TokenAuthConfig from system-assigned Managed Identity "
								+ "(scopes={}, refreshRatio={}, lowerRefreshMs={})",
						scopes, refreshRatio, lowerRefreshMs);
			}
			case USER_ASSIGNED_CLIENT_ID -> {
				builder.userAssignedManagedIdentity(UserManagedIdentityType.CLIENT_ID, identity.id());
				log.info(
						"Building TokenAuthConfig from user-assigned Managed Identity by clientId={} "
								+ "(scopes={}, refreshRatio={}, lowerRefreshMs={})",
						mask(identity.id()), scopes, refreshRatio, lowerRefreshMs);
			}
			case USER_ASSIGNED_OBJECT_ID -> {
				builder.userAssignedManagedIdentity(UserManagedIdentityType.OBJECT_ID, identity.id());
				log.info(
						"Building TokenAuthConfig from user-assigned Managed Identity by objectId={} "
								+ "(scopes={}, refreshRatio={}, lowerRefreshMs={})",
						mask(identity.id()), scopes, refreshRatio, lowerRefreshMs);
			}
			case USER_ASSIGNED_RESOURCE_ID -> {
				builder.userAssignedManagedIdentity(UserManagedIdentityType.RESOURCE_ID, identity.id());
				log.info(
						"Building TokenAuthConfig from user-assigned Managed Identity by resourceId={} "
								+ "(scopes={}, refreshRatio={}, lowerRefreshMs={})",
						identity.id(), scopes, refreshRatio, lowerRefreshMs);
			}
		}

		return builder.build();
	}

	private static Set<String> scopes(AmrRedisProperties.Entra entra) {
		String scope = entra != null && entra.tokenScope() != null && !entra.tokenScope().isBlank() ? entra.tokenScope()
				: DEFAULT_AMR_SCOPE;
		return Set.of(scope);
	}

	private static float refreshRatio(AmrRedisProperties.Entra entra) {
		return entra != null && entra.expirationRefreshRatio() > 0f ? entra.expirationRefreshRatio()
				: DEFAULT_TOKEN_REFRESH_RATIO;
	}

	private static int lowerRefreshBoundMs(AmrRedisProperties.Entra entra) {
		return entra != null && entra.lowerRefreshBound() != null ? (int) entra.lowerRefreshBound().toMillis()
				: DEFAULT_TOKEN_LOWER_REFRESH_MS;
	}

	private static int tokenRequestTimeoutMs(AmrRedisProperties.Entra entra) {
		return entra != null && entra.tokenRequestTimeout() != null ? (int) entra.tokenRequestTimeout().toMillis()
				: DEFAULT_TOKEN_REQUEST_TIMEOUT_MS;
	}

	private static String mask(String id) {
		if (id == null || id.length() < 8) {
			return "***";
		}
		return id.substring(0, 4) + "..." + id.substring(id.length() - 4);
	}

}
