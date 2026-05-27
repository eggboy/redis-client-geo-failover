package io.jaylee.amr.demo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "app.workload")
public record WorkloadProperties(boolean backgroundWriterEnabled, Duration backgroundWriterInterval,
		String backgroundWriterKey) {
}
