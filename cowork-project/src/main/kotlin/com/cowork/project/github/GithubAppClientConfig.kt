package com.cowork.project.github

import tools.jackson.databind.ObjectMapper
import feign.RequestInterceptor
import feign.codec.ErrorDecoder
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean

private const val INTERNAL_API_KEY_HEADER = "X-Internal-Api-Key"

/**
 * `GithubAppClient` 전용 Feign 설정. `@Configuration`을 붙이지 않아야 이 빈들이
 * 메인 컴포넌트 스캔에 포함되지 않고 `github-app` 클라이언트의 자식 컨텍스트에만 적용된다.
 */
class GithubAppClientConfig(
    @Value("\${github-app.internal-api-key}") private val internalApiKey: String,
) {

    @Bean
    fun githubAppRequestInterceptor(): RequestInterceptor =
        RequestInterceptor { template -> template.header(INTERNAL_API_KEY_HEADER, internalApiKey) }

    @Bean
    fun githubAppErrorDecoder(objectMapper: ObjectMapper): ErrorDecoder = GithubAppErrorDecoder(objectMapper)
}
