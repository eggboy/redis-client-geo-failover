package io.jaylee.amr.demo.domain;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A user session stored as a single JSON document in Redis at
 * {@code session:{sessionId}}.
 *
 * <p>
 * Design notes (see ADR-0004):
 * <ul>
 * <li>{@code sessionId} is a server-issued UUID. {@code userId} is client-supplied and is
 * <b>not</b> unique — multiple sessions per user are allowed, matching how real session
 * stores model multi-device login.</li>
 * <li>{@code lastSeenAt} reflects the last <b>mutation</b> (POST/PATCH); it is
 * intentionally NOT updated on GET. GET extends the TTL atomically via {@code GETEX}
 * without rewriting the JSON document. Rewriting on every read would turn the read
 * workload into cross-region replicated writes and pollute the demo's failover and
 * latency measurements.</li>
 * <li>Concurrent PATCHes to the same {@code sessionId} are last-writer-wins.</li>
 * </ul>
 */
public record Session(String sessionId, String userId, Instant createdAt, Instant lastSeenAt,
		Map<String, String> attributes) {

	/**
	 * Factory — validates inputs and mints a new session with a server-issued UUID.
	 */
	public static Session create(String userId, Map<String, String> attributes) {
		if (userId == null || userId.isBlank()) {
			throw new IllegalArgumentException("userId must not be blank");
		}
		Instant now = Instant.now();
		return new Session(UUID.randomUUID().toString(), userId, now, now, validated(attributes));
	}

	/**
	 * Returns a new {@code Session} with {@code patchAttributes} shallow-merged over the
	 * current attributes and {@code lastSeenAt} advanced to now.
	 */
	public Session merge(Map<String, String> patchAttributes) {
		Map<String, String> merged = new LinkedHashMap<>(this.attributes);
		merged.putAll(validated(patchAttributes));
		return new Session(sessionId, userId, createdAt, Instant.now(), merged);
	}

	private static Map<String, String> validated(Map<String, String> attributes) {
		if (attributes == null) {
			return Map.of();
		}
		for (Map.Entry<String, String> e : attributes.entrySet()) {
			if (e.getKey() == null || e.getKey().isBlank()) {
				throw new IllegalArgumentException("attribute name must not be blank");
			}
			if (e.getValue() == null) {
				throw new IllegalArgumentException("attribute '" + e.getKey() + "' must not have a null value");
			}
		}
		return new LinkedHashMap<>(attributes);
	}

}
