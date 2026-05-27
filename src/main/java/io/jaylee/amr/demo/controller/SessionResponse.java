package io.jaylee.amr.demo.controller;

import io.jaylee.amr.demo.domain.Session;

import java.time.Instant;
import java.util.Map;

/**
 * REST response DTO for session endpoints. Decouples the API contract from the
 * {@link Session} domain object so that domain field renames do not accidentally change
 * the HTTP response shape.
 */
public record SessionResponse(String sessionId, String userId, Instant createdAt, Instant lastSeenAt,
		Map<String, String> attributes) {

	public static SessionResponse from(Session session) {
		return new SessionResponse(session.sessionId(), session.userId(), session.createdAt(), session.lastSeenAt(),
				session.attributes());
	}

}
