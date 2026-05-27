package io.jaylee.amr.demo.repository;

import io.jaylee.amr.demo.domain.Session;

import java.time.Duration;
import java.util.Optional;

/**
 * Persistence contract for sessions. {@link JedisSessionRepository} (package-private in
 * this package) provides the Redis-backed implementation.
 */
public interface SessionRepository {

	/**
	 * Persist {@code session} with the given TTL, overwriting any existing value.
	 * @return the session as stored (same instance is fine — no server-side mutation)
	 */
	Session save(Session session, Duration ttl);

	/**
	 * Load a session and atomically extend its TTL (GETEX). Returns empty if the key does
	 * not exist or has expired.
	 */
	Optional<Session> findAndRefresh(String sessionId, Duration ttl);

	/**
	 * Load a session without altering its TTL. Used before a write-back (PATCH) where the
	 * subsequent {@link #save} will reset the TTL anyway.
	 */
	Optional<Session> find(String sessionId);

	/**
	 * Remove the session. Idempotent — returns {@code true} only when the key actually
	 * existed.
	 */
	boolean delete(String sessionId);

}
