package com.example.attractionreservation.infra.portone.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@RequiredArgsConstructor
public class PortOneConfig {
    private final PortOneProperties properties;

    // Spring Boot가 제공하는 Builder를 사용해야 요청 메트릭(http.client.requests)과 traceId 전파가 적용된다.
    @Bean
    public RestClient portoneRestClient(RestClient.Builder restClientBuilder) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(5000);
        requestFactory.setReadTimeout(10000);

        return restClientBuilder
                .requestFactory(requestFactory)
                .baseUrl(properties.getBaseUrl())
                .defaultHeader("Authorization", "PortOne " + properties.getApiSecret())
                .build();
    }
}
