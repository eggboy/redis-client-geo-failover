package io.jaylee.amr.demo.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Constructor-level validation for {@link AmrRedisProperties.ClientTimeouts}. These
 * checks guard against silently misconfigured Jedis socket/connect timeouts (zero,
 * negative, sub-millisecond, or overflowing the int milliseconds cast Jedis requires).
 */
class AmrRedisPropertiesClientTimeoutsTest {

	@Test
	void acceptsPositiveDurations() {
		var timeouts = new AmrRedisProperties.ClientTimeouts(Duration.ofSeconds(2), Duration.ofSeconds(5));
		assertThat(timeouts.connectTimeout()).hasSeconds(2);
		assertThat(timeouts.socketTimeout()).hasSeconds(5);
	}

	@Test
	void rejectsNullConnectTimeout() {
		assertThatThrownBy(() -> new AmrRedisProperties.ClientTimeouts(null, Duration.ofSeconds(5)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("connect-timeout");
	}

	@Test
	void rejectsNullSocketTimeout() {
		assertThatThrownBy(() -> new AmrRedisProperties.ClientTimeouts(Duration.ofSeconds(2), null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("socket-timeout");
	}

	@Test
	void rejectsZeroDuration() {
		assertThatThrownBy(() -> new AmrRedisProperties.ClientTimeouts(Duration.ZERO, Duration.ofSeconds(5)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("connect-timeout");
	}

	@Test
	void rejectsNegativeDuration() {
		assertThatThrownBy(() -> new AmrRedisProperties.ClientTimeouts(Duration.ofSeconds(2), Duration.ofSeconds(-1)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("socket-timeout");
	}

	@Test
	void rejectsDurationOverflowingInt() {
		Duration tooBig = Duration.ofMillis((long) Integer.MAX_VALUE + 1);
		assertThatThrownBy(() -> new AmrRedisProperties.ClientTimeouts(tooBig, Duration.ofSeconds(5)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("connect-timeout");
	}

}
