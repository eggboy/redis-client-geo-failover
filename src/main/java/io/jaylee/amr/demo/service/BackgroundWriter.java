package io.jaylee.amr.demo.service;

import io.jaylee.amr.demo.config.WorkloadProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import redis.clients.jedis.MultiDbClient;

@Component
@ConditionalOnProperty(value = "app.workload.background-writer-enabled", havingValue = "true", matchIfMissing = true)
public class BackgroundWriter {

	private static final Logger log = LoggerFactory.getLogger(BackgroundWriter.class);

	private final MultiDbClient client;

	private final WorkloadProperties props;

	public BackgroundWriter(MultiDbClient client, WorkloadProperties props) {
		this.client = client;
		this.props = props;
	}

	@PostConstruct
	void announce() {
		log.info("BackgroundWriter active: key={}, interval={}", props.backgroundWriterKey(),
				props.backgroundWriterInterval());
	}

	@Scheduled(fixedDelayString = "${app.workload.background-writer-interval:PT1S}")
	public void tick() {
		try {
			Long value = client.incr(props.backgroundWriterKey());
			log.info("INCR {} = {}  (active={})", props.backgroundWriterKey(), value,
					client.getActiveDatabaseEndpoint());
		}
		catch (RuntimeException ex) {
			log.warn("INCR failed (active={}): {}", client.getActiveDatabaseEndpoint(), ex.toString());
		}
	}

}
