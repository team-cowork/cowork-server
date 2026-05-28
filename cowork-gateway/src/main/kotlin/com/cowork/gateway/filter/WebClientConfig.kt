package com.cowork.gateway.filter

import org.springframework.cloud.client.loadbalancer.LoadBalanced
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.reactive.function.client.WebClient

@Configuration
class WebClientConfig {

    @Bean("lbWebClientBuilder")
    @LoadBalanced
    fun lbWebClientBuilder(): WebClient.Builder = WebClient.builder()
}
