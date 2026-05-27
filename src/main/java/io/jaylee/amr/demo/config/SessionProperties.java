package io.jaylee.amr.demo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Configuration for the session store.
 *
 * @param defaultTtl sliding TTL applied to every session on write and refreshed (via
 * {@code GETEX}) on read. Defaults to 30 minutes — a typical web-session lifetime. Must
 * be at least 1 second because {@code SET EX} / {@code GETEX EX} take whole-second
 * arguments — {@link java.time.Duration#toSeconds()} floors sub-second values, and Redis
 * rejects {@code EX 0}.
 */
@ConfigurationProperties(prefix = "app.session")
public record SessionProperties(Duration defaultTtl) {

	public SessionProperties {
		if (defaultTtl == null || defaultTtl.isNegative() || defaultTtl.toSeconds() < 1) {
			throw new IllegalArgumentException(
					"app.session.default-ttl must be at least 1 second (got " + defaultTtl + ")");
		}
	}
}
