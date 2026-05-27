package io.jaylee.amr.demo.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.RedisClient;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * Holder of one Jedis {@link RedisClient} per configured AMR endpoint, bypassing
 * {@code MultiDbClient}'s routing entirely.
 *
 * <p>
 * <b>TEST USE ONLY.</b> Production traffic must go through {@code MultiDbClient}. These
 * pinned connections exist solely to support Scenario 1 — see ADR-0002.
 */
public final class PinnedConnections implements AutoCloseable {

	private static final Logger log = LoggerFactory.getLogger(PinnedConnections.class);

	private final Map<String, RedisClient> clients;

	public PinnedConnections(Map<String, RedisClient> clients) {
		this.clients = Collections.unmodifiableMap(new LinkedHashMap<>(clients));
	}

	public RedisClient get(String endpointName) {
		RedisClient client = clients.get(endpointName);
		if (client == null) {
			throw new NoSuchElementException(
					"No pinned connection for endpoint '" + endpointName + "'. Known: " + clients.keySet());
		}
		return client;
	}

	public boolean contains(String endpointName) {
		return clients.containsKey(endpointName);
	}

	public Set<String> endpointNames() {
		return clients.keySet();
	}

	@Override
	public void close() {
		for (Map.Entry<String, RedisClient> entry : clients.entrySet()) {
			try {
				entry.getValue().close();
			}
			catch (RuntimeException e) {
				log.debug("Pinned client close failed for endpoint '{}': {}", entry.getKey(), e.toString());
			}
		}
	}

}
