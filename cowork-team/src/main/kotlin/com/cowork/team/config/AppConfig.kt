package com.cowork.team.config

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestClient

@Configuration
@EnableConfigurationProperties(MinioProperties::class, PreferenceProperties::class)
class AppConfig {

    @Bean
    fun preferenceRestClient(properties: PreferenceProperties): RestClient =
        RestClient.builder()
            .baseUrl(properties.baseUrl)
            .build()
}
