package io.jaylee.amr.demo.repository;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the Redis key scheme so the routed write path ({@code JedisSessionRepository}) and
 * the pinned read path ({@code PinnedSessionAdminController}) can never drift.
 */
class SessionStorageKeysTest {

	@Test
	void prependsSessionPrefix() {
		assertThat(SessionStorageKeys.keyFor("abc-123")).isEqualTo("session:abc-123");
	}

	@Test
	void prefixConstantMatchesKeyFor() {
		assertThat(SessionStorageKeys.KEY_PREFIX + "xyz").isEqualTo(SessionStorageKeys.keyFor("xyz"));
	}

}
