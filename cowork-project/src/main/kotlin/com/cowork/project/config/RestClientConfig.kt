package com.cowork.project.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.time.Duration

/**
 * `RestClient.Builder`가 이 환경에서 자동 등록되지 않아 명시적으로 빈을 정의한다.
 *
 * `spring-boot-autoconfigure`에 `RestClientAutoConfiguration`이 포함되어 있지 않아
 * `spring.http.client.connect-timeout`/`read-timeout` 설정이 자동으로 적용되지 않으므로,
 * 동일한 설정값을 직접 읽어 `ClientHttpRequestFactory`에 명시적으로 적용한다.
 */
@Configuration
class RestClientConfig(
    @Value("\${spring.http.client.connect-timeout}") private val connectTimeout: Duration,
    @Value("\${spring.http.client.read-timeout}") private val readTimeout: Duration,
) {

    @Bean
    fun restClientBuilder(): RestClient.Builder {
        val requestFactory = SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(connectTimeout)
            setReadTimeout(readTimeout)
        }
        return RestClient.builder().requestFactory(requestFactory)
    }
}
