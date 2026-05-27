package io.jaylee.amr.demo.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import redis.clients.jedis.mcf.DatabaseSwitchEvent;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Single source of truth for {@link DatabaseSwitchEvent} observations. Tracks the
 * "previous" endpoint, logs the transition, exposes the latest switch via
 * {@link #getLastSwitch()}, and forwards each transition to {@link EventRingBuffer} for
 * the {@code /events} endpoint.
 *
 * <p>
 * This is the sole listener wired in {@code RedisMultiDbConfig} — {@code EventRingBuffer}
 * is not a Jedis listener itself; it receives events from this class.
 */
@Component
public class SwitchEventLogger {

	private static final Logger log = LoggerFactory.getLogger(SwitchEventLogger.class);

	private final EventRingBuffer eventRingBuffer;

	private final AtomicReference<LastSwitch> lastSwitch = new AtomicReference<>();

	private final AtomicReference<String> previousEndpoint = new AtomicReference<>("unknown");

	public SwitchEventLogger(EventRingBuffer eventRingBuffer) {
		this.eventRingBuffer = eventRingBuffer;
	}

	/**
	 * Called by the {@code MultiDbClient}'s database switch listener configured in
	 * {@code RedisMultiDbConfig}.
	 */
	public void handle(DatabaseSwitchEvent event) {
		String to = String.valueOf(event.getEndpoint());
		String from = previousEndpoint.getAndSet(to);
		String reason = String.valueOf(event.getReason());
		LastSwitch transition = new LastSwitch(Instant.now(), reason, from, to);
		log.warn("[FAILOVER] reason={} from={} to={}", reason, from, to);
		lastSwitch.set(transition);
		eventRingBuffer.recordSwitch(transition);
	}

	public LastSwitch getLastSwitch() {
		return lastSwitch.get();
	}

	public record LastSwitch(Instant at, String reason, String fromDb, String toDb) {
	}

}
