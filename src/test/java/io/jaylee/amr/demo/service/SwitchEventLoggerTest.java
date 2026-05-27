package io.jaylee.amr.demo.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import redis.clients.jedis.Endpoint;
import redis.clients.jedis.mcf.DatabaseSwitchEvent;
import redis.clients.jedis.mcf.SwitchReason;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies that {@link SwitchEventLogger} is the sole source of truth for switch
 * transitions: it tracks "previous", produces one {@link SwitchEventLogger.LastSwitch},
 * and forwards exactly one record to {@link EventRingBuffer}. Replaces the previous
 * design where both classes independently subscribed to {@code DatabaseSwitchEvent} and
 * each tracked their own {@code previousEndpoint}.
 */
class SwitchEventLoggerTest {

	@Test
	void firstSwitchReportsUnknownAsPreviousAndForwardsToRingBuffer() {
		EventRingBuffer ringBuffer = mock(EventRingBuffer.class);
		SwitchEventLogger logger = new SwitchEventLogger(ringBuffer);

		logger.handle(switchEventTo("primary.example.com", 10000, SwitchReason.CIRCUIT_BREAKER));

		var last = logger.getLastSwitch();
		assertThat(last).isNotNull();
		assertThat(last.fromDb()).isEqualTo("unknown");
		assertThat(last.toDb()).contains("primary.example.com");
		assertThat(last.reason()).isEqualTo("CIRCUIT_BREAKER");

		ArgumentCaptor<SwitchEventLogger.LastSwitch> captor = ArgumentCaptor
			.forClass(SwitchEventLogger.LastSwitch.class);
		verify(ringBuffer).recordSwitch(captor.capture());
		assertThat(captor.getValue()).isEqualTo(last);
	}

	@Test
	void secondSwitchReportsPreviousEndpointAsFrom() {
		EventRingBuffer ringBuffer = mock(EventRingBuffer.class);
		SwitchEventLogger logger = new SwitchEventLogger(ringBuffer);

		logger.handle(switchEventTo("primary.example.com", 10000, SwitchReason.CIRCUIT_BREAKER));
		logger.handle(switchEventTo("secondary.example.com", 10000, SwitchReason.FAILBACK));

		var last = logger.getLastSwitch();
		assertThat(last.fromDb()).contains("primary.example.com");
		assertThat(last.toDb()).contains("secondary.example.com");
		assertThat(last.reason()).isEqualTo("FAILBACK");
	}

	private static DatabaseSwitchEvent switchEventTo(String host, int port, SwitchReason reason) {
		Endpoint endpoint = mock(Endpoint.class);
		when(endpoint.toString()).thenReturn(host + ":" + port);
		DatabaseSwitchEvent event = mock(DatabaseSwitchEvent.class);
		when(event.getEndpoint()).thenReturn(endpoint);
		when(event.getReason()).thenReturn(reason);
		return event;
	}

}
