package io.jaylee.amr.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class AmrGeoFailoverApplication {

	public static void main(String[] args) {
		SpringApplication.run(AmrGeoFailoverApplication.class, args);
	}

}
