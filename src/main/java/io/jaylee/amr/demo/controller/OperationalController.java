package io.jaylee.amr.demo.controller;

import io.jaylee.amr.demo.service.EventRingBuffer;
import io.jaylee.amr.demo.service.SwitchEventLogger;
import redis.clients.jedis.Endpoint;
import redis.clients.jedis.MultiDbClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
public class OperationalController {

	private static final int DEFAULT_EVENT_LIMIT = 50;

	private static final int MAX_EVENT_LIMIT = EventRingBuffer.CAPACITY;

	private final MultiDbClient client;

	private final SwitchEventLogger switchEventLogger;

	private final EventRingBuffer eventRingBuffer;

	public OperationalController(MultiDbClient client, SwitchEventLogger switchEventLogger,
			EventRingBuffer eventRingBuffer) {
		this.client = client;
		this.switchEventLogger = switchEventLogger;
		this.eventRingBuffer = eventRingBuffer;
	}

	@GetMapping("/status")
	public Map<String, Object> status() {
		Endpoint active = client.getActiveDatabaseEndpoint();
		List<Map<String, Object>> endpoints = new ArrayList<>();
		for (Endpoint endpoint : client.getDatabaseEndpoints()) {
			Map<String, Object> e = new LinkedHashMap<>();
			e.put("endpoint", endpoint.toString());
			e.put("healthy", client.isHealthy(endpoint));
			e.put("weight", client.getWeight(endpoint));
			e.put("active", endpoint.equals(active));
			endpoints.add(e);
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("activeEndpoint", String.valueOf(active));
		out.put("endpoints", endpoints);
		out.put("lastSwitch", switchEventLogger.getLastSwitch());
		return out;
	}

	@GetMapping("/events")
	public Map<String, Object> events(@RequestParam(name = "since", required = false) String since,
			@RequestParam(name = "type", required = false) String type,
			@RequestParam(name = "limit", required = false, defaultValue = "" + DEFAULT_EVENT_LIMIT) int limit) {

		Instant sinceTs = parseSince(since);
		int effectiveLimit = Math.max(1, Math.min(limit, MAX_EVENT_LIMIT));
		List<EventRingBuffer.Entry> entries = eventRingBuffer.snapshot(sinceTs, type, effectiveLimit);

		Map<String, Object> out = new LinkedHashMap<>();
		out.put("now", Instant.now());
		out.put("count", entries.size());
		out.put("events", entries);
		return out;
	}

	private static Instant parseSince(String since) {
		if (since == null || since.isBlank())
			return null;
		try {
			return Instant.parse(since);
		}
		catch (DateTimeParseException e) {
			throw new IllegalArgumentException(
					"Invalid 'since' value '" + since + "'; expected ISO-8601 UTC (e.g. 2026-05-17T20:04:00.000Z)");
		}
	}

}
