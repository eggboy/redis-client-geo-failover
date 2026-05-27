package io.jaylee.amr.demo.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Compact-constructor validation for {@link SessionProperties}. Guards against sub-second
 * TTLs, which would silently truncate to {@code EX 0} via {@link Duration#toSeconds()}
 * and be rejected by Redis at runtime.
 */
class SessionPropertiesTest {

	@Test
	void acceptsOneSecondTtl() {
		var props = new SessionProperties(Duration.ofSeconds(1));
		assertThat(props.defaultTtl()).hasSeconds(1);
	}

	@Test
	void acceptsThirtyMinuteTtl() {
		var props = new SessionProperties(Duration.ofMinutes(30));
		assertThat(props.defaultTtl()).hasSeconds(1800);
	}

	@Test
	void rejectsNull() {
		assertThatThrownBy(() -> new SessionProperties(null)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("at least 1 second");
	}

	@Test
	void rejectsZero() {
		assertThatThrownBy(() -> new SessionProperties(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("at least 1 second");
	}

	@Test
	void rejectsNegative() {
		assertThatThrownBy(() -> new SessionProperties(Duration.ofSeconds(-5)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("at least 1 second");
	}

	@Test
	void rejectsSubSecondTtlToPreventSetExZero() {
		assertThatThrownBy(() -> new SessionProperties(Duration.ofMillis(500)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("at least 1 second");
	}

}
