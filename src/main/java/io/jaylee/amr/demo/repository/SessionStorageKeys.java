package io.jaylee.amr.demo.repository;

/**
 * Single source of truth for the Redis key scheme used to store sessions. Both
 * {@link JedisSessionRepository} (the normal routed write/read path) and
 * {@code PinnedSessionAdminController} (the test-only pinned-endpoint read path used by
 * Scenario 1) derive keys via this utility, so the scheme is defined exactly once.
 */
public final class SessionStorageKeys {

	public static final String KEY_PREFIX = "session:";

	private SessionStorageKeys() {
	}

	public static String keyFor(String sessionId) {
		return KEY_PREFIX + sessionId;
	}

}
