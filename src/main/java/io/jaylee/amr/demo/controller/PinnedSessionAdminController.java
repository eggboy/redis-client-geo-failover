package io.jaylee.amr.demo.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jaylee.amr.demo.service.PinnedConnections;
import io.jaylee.amr.demo.domain.Session;
import io.jaylee.amr.demo.repository.SessionStorageKeys;
import redis.clients.jedis.RedisClient;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read a session directly from a named AMR endpoint, bypassing {@code MultiDbClient}'s
 * routing.
 *
 * <p>
 * <b>TEST USE ONLY.</b> Gated with {@code @Profile("!prod")} so a production profile
 * (e.g. {@code SPRING_PROFILES_ACTIVE=prod}) cannot expose it. Used by Scenario 1 to make
 * round-trip assertions that don't race the failback timer. See ADR-0002.
 *
 * <p>
 * Returns the stored Session JSON as-is, or 404 if the key is missing.
 */
@RestController
@RequestMapping("/admin/pinned/{endpoint}/sessions")
@Profile("!prod")
public class PinnedSessionAdminController {

	private final PinnedConnections pinned;

	private final ObjectMapper objectMapper;

	public PinnedSessionAdminController(PinnedConnections pinned, ObjectMapper objectMapper) {
		this.pinned = pinned;
		this.objectMapper = objectMapper;
	}

	@GetMapping("/{sessionId}")
	public ResponseEntity<SessionResponse> get(@PathVariable String endpoint, @PathVariable String sessionId) {
		RedisClient pinnedClient = pinned.get(endpoint);
		String json = pinnedClient.get(SessionStorageKeys.keyFor(sessionId));
		if (json == null) {
			return ResponseEntity.notFound().build();
		}
		try {
			Session session = objectMapper.readValue(json, Session.class);
			return ResponseEntity.ok(SessionResponse.from(session));
		}
		catch (JsonProcessingException ex) {
			throw new IllegalStateException(
					"Failed to deserialise session JSON from endpoint '" + endpoint + "': " + ex.getOriginalMessage(),
					ex);
		}
	}

}
