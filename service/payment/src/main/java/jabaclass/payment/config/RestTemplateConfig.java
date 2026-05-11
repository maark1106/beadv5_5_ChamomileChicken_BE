package jabaclass.payment.config;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration
public class RestTemplateConfig {

	@Bean
	public RestTemplate restTemplate(ObservationRegistry observationRegistry) {
		RestTemplate restTemplate = new RestTemplate();
		restTemplate.setObservationRegistry(observationRegistry);
		return restTemplate;
	}
}