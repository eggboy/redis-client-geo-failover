package io.jaylee.amr.demo.controller;

import io.jaylee.amr.demo.config.AmrRedisProperties;
import redis.clients.jedis.Endpoint;
import redis.clients.jedis.MultiDbClient;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.NoSuchElementException;

@RestController
@RequestMapping("/redis/active-endpoint")
public class FailoverController {

	private final MultiDbClient client;

	private final AmrRedisProperties props;

	public FailoverController(MultiDbClient client, AmrRedisProperties props) {
		this.client = client;
		this.props = props;
	}

	@PutMapping
	public SwitchResponse switchTo(@RequestBody ActiveEndpointRequest request) {
		String endpointName = request == null || request.endpoint() == null ? "" : request.endpoint();
		AmrRedisProperties.Endpoint target = props.endpoints()
			.stream()
			.filter(e -> e.name().equalsIgnoreCase(endpointName))
			.findFirst()
			.orElseThrow(() -> new NoSuchElementException("Unknown endpoint name '" + endpointName + "'. Known: "
					+ props.endpoints().stream().map(AmrRedisProperties.Endpoint::name).toList()));

		Endpoint registered = client.getDatabaseEndpoints()
			.stream()
			.filter(ep -> ep.getHost().equals(target.host()) && ep.getPort() == target.port())
			.findFirst()
			.orElseThrow(() -> new IllegalStateException(
					"Endpoint '" + endpointName + "' is configured but not registered with the MultiDbClient"));

		String before = String.valueOf(client.getActiveDatabaseEndpoint());
		client.setActiveDatabase(registered);
		return new SwitchResponse(endpointName, before, String.valueOf(client.getActiveDatabaseEndpoint()));
	}

	public record ActiveEndpointRequest(String endpoint) {
	}

	public record SwitchResponse(String requested, String from, String to) {
	}

}
