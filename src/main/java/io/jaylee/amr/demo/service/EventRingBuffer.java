package io.jaylee.amr.demo.service;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Bounded in-memory ring buffer of recent failover events. Backs {@code GET /events} so
 * scenario scripts can make programmatic assertions instead of grepping application logs.
 *
 * <p>
 * Receives transitions from {@link SwitchEventLogger} (the sole
 * {@code DatabaseSwitchEvent} listener) — this class is not itself a Jedis listener.
 *
 * <p>
 * Single writer (Jedis callback thread, via {@code SwitchEventLogger}), many readers
 * (HTTP handlers). Synchronized {@link ArrayDeque} is sufficient — events fire at the
 * human-perceivable timescale of failovers, not high-frequency telemetry, so lock
 * contention is a non-issue.
 */
@Component
public class EventRingBuffer {

	public static final int CAPACITY = 200;

	public static final String TYPE_SWITCH = "DatabaseSwitchEvent";

	private final Deque<Entry> buffer = new ArrayDeque<>(CAPACITY);

	/**
	 * Record a database-switch transition. Called by {@link SwitchEventLogger} after it
	 * has computed the from/to/reason for the event.
	 */
	public void recordSwitch(SwitchEventLogger.LastSwitch transition) {
		push(new Entry(transition.at(), TYPE_SWITCH, transition.reason(), transition.fromDb(), transition.toDb()));
	}

	private void push(Entry entry) {
		synchronized (buffer) {
			if (buffer.size() == CAPACITY) {
				buffer.removeFirst();
			}
			buffer.addLast(entry);
		}
	}

	/**
	 * Returns up to {@code limit} entries newer than {@code since} (exclusive),
	 * optionally filtered by {@code type}. Result is in chronological order (oldest
	 * first).
	 */
	public List<Entry> snapshot(Instant since, String type, int limit) {
		List<Entry> out = new ArrayList<>(Math.min(limit, CAPACITY));
		synchronized (buffer) {
			for (Entry e : buffer) {
				if (since != null && !e.ts().isAfter(since))
					continue;
				if (type != null && !type.isBlank() && !type.equals(e.type()))
					continue;
				out.add(e);
				if (out.size() >= limit)
					break;
			}
		}
		return out;
	}

	public record Entry(Instant ts, String type, String reason, String from, String to) {
	}

}
