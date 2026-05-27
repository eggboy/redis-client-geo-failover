package io.jaylee.amr.demo.service;

import io.jaylee.amr.demo.config.SessionProperties;
import io.jaylee.amr.demo.domain.Session;
import io.jaylee.amr.demo.repository.SessionRepository;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;

/**
 * Session-store application service. Orchestrates domain operations ({@link Session}
 * factory and merge) with persistence ({@link SessionRepository}). Has no knowledge of
 * Redis, serialisation, key naming, or controller request DTOs.
 *
 * @see Session
 * @see SessionRepository
 */
@Service
public class SessionService {

	private final SessionRepository repository;

	private final SessionProperties props;

	public SessionService(SessionRepository repository, SessionProperties props) {
		this.repository = repository;
		this.props = props;
	}

	public Session create(String userId, Map<String, String> attributes) {
		Session session = Session.create(userId, attributes);
		return repository.save(session, props.defaultTtl());
	}

	/**
	 * Read a session and slide its TTL atomically. Does NOT rewrite the JSON document
	 * (lastSeenAt is unchanged), avoiding cross-region replicated writes on every read.
	 */
	public Optional<Session> get(String sessionId) {
		requireNonBlank(sessionId, "sessionId");
		return repository.findAndRefresh(sessionId, props.defaultTtl());
	}

	/**
	 * Shallow-merge attributes, bump {@code lastSeenAt}, and reset TTL. Last writer wins
	 * on concurrent calls.
	 */
	public Optional<Session> patch(String sessionId, Map<String, String> attributes) {
		requireNonBlank(sessionId, "sessionId");
		return repository.find(sessionId).map(existing -> {
			Session updated = existing.merge(attributes);
			return repository.save(updated, props.defaultTtl());
		});
	}

	/** Idempotent. Returns true if the key actually existed and was removed. */
	public boolean delete(String sessionId) {
		requireNonBlank(sessionId, "sessionId");
		return repository.delete(sessionId);
	}

	private static void requireNonBlank(String value, String name) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(name + " must not be blank");
		}
	}

}
