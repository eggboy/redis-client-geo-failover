package io.jaylee.amr.demo.service;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.stereotype.Component;
import redis.clients.jedis.Endpoint;
import redis.clients.jedis.MultiDbClient;

import java.util.LinkedHashMap;
import java.util.Map;

@Component("amrMultiDb")
public class AmrMultiDbHealthIndicator implements HealthIndicator {

	private final MultiDbClient client;

	public AmrMultiDbHealthIndicator(MultiDbClient client) {
		this.client = client;
	}

	@Override
	public Health health() {
		try {
			Map<String, Object> endpoints = new LinkedHashMap<>();
			boolean anyHealthy = false;
			for (Endpoint endpoint : client.getDatabaseEndpoints()) {
				boolean healthy = client.isHealthy(endpoint);
				anyHealthy |= healthy;
				Map<String, Object> detail = new LinkedHashMap<>();
				detail.put("endpoint", endpoint.toString());
				detail.put("healthy", healthy);
				detail.put("weight", client.getWeight(endpoint));
				detail.put("active", endpoint.equals(client.getActiveDatabaseEndpoint()));
				endpoints.put(endpoint.toString(), detail);
			}

			return new Health.Builder(anyHealthy ? Status.UP : Status.DOWN)
				.withDetail("activeEndpoint", String.valueOf(client.getActiveDatabaseEndpoint()))
				.withDetail("endpoints", endpoints)
				.build();
		}
		catch (RuntimeException ex) {
			return Health.down()
				.withDetail("reason", "Failed to inspect MultiDbClient state")
				.withException(ex)
				.build();
		}
	}

}
