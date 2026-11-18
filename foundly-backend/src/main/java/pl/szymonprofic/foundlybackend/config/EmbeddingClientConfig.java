package pl.szymonprofic.foundlybackend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
public class EmbeddingClientConfig {
    @Bean
    public RestClient embeddingRestClient(
            @Value("${foundly.embedding.base-url}") String baseUrl,
            @Value("${foundly.embedding.connect-timeout}") Duration connectTimeout,
            @Value("${foundly.embedding.read-timeout}") Duration readTimeout) {
        if (connectTimeout.toMillis() <= 0 || readTimeout.toMillis() <= 0)
            throw new IllegalArgumentException("Embedding client timeouts must be at least 1 ms");

        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }
}
