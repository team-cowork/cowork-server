package com.cowork.roadmap.global.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class WebClientConfig {

    @Bean
    @LoadBalanced
    public WebClient.Builder loadBalancedWebClientBuilder() {
        return WebClient.builder();
    }

    @Bean
    public WebClient teamWebClient(WebClient.Builder loadBalancedWebClientBuilder,
            @Value("${team-service.base-url}") String baseUrl) {
        return loadBalancedWebClientBuilder.baseUrl(baseUrl).build();
    }
}
