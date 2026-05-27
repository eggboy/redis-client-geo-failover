package io.jaylee.amr.demo.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jaylee.amr.demo.domain.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;
import redis.clients.jedis.MultiDbClient;
import redis.clients.jedis.params.GetExParams;
import redis.clients.jedis.params.SetParams;

import java.time.Duration;
import java.util.Optional;

/**
 * Jedis-backed implementation of {@link SessionRepository}.
 *
 * <p>
 * Encapsulates all Redis mapping concerns: key scheme ({@code session:{sid}}), JSON
 * serialisation, TTL management, and the distinction between {@code SET EX} (save) and
 * {@code GETEX} (read-with-refresh). The session domain has no knowledge of these
 * details.
 */
@Repository
class JedisSessionRepository implements SessionRepository {

	private static final Logger log = LoggerFactory.getLogger(JedisSessionRepository.class);

	private final MultiDbClient client;

	private final ObjectMapper objectMapper;

	JedisSessionRepository(MultiDbClient client, ObjectMapper objectMapper) {
		this.client = client;
		this.objectMapper = objectMapper;
	}

	@Override
	public Session save(Session session, Duration ttl) {
		String reply = client.set(SessionStorageKeys.keyFor(session.sessionId()), serialize(session),
				SetParams.setParams().ex(ttl.toSeconds()));
		if (!"OK".equals(reply)) {
			throw new IllegalStateException("Unexpected SET reply for session " + session.sessionId() + ": " + reply);
		}
		log.debug("Saved session {} (ttl={})", session.sessionId(), ttl);
		return session;
	}

	@Override
	public Optional<Session> findAndRefresh(String sessionId, Duration ttl) {
		String json = client.getEx(SessionStorageKeys.keyFor(sessionId), GetExParams.getExParams().ex(ttl.toSeconds()));
		return Optional.ofNullable(json).map(this::deserialize);
	}

	@Override
	public Optional<Session> find(String sessionId) {
		return Optional.ofNullable(client.get(SessionStorageKeys.keyFor(sessionId))).map(this::deserialize);
	}

	@Override
	public boolean delete(String sessionId) {
		return client.del(SessionStorageKeys.keyFor(sessionId)) > 0;
	}

	private String serialize(Session session) {
		try {
			return objectMapper.writeValueAsString(session);
		}
		catch (JsonProcessingException ex) {
			throw new IllegalStateException("Failed to serialise session " + session.sessionId(), ex);
		}
	}

	private Session deserialize(String json) {
		try {
			return objectMapper.readValue(json, Session.class);
		}
		catch (JsonProcessingException ex) {
			throw new IllegalStateException("Failed to deserialise session JSON: " + ex.getOriginalMessage(), ex);
		}
	}

}
