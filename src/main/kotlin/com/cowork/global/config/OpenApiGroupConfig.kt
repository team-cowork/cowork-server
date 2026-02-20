package com.cowork.global.config

import org.springdoc.core.models.GroupedOpenApi
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Swagger UI에서 도메인별로 API를 나누어 본다.
 */
@Configuration
class OpenApiGroupConfig {

    @Bean
    fun allApi(): GroupedOpenApi = group("all", "com.cowork")

    @Bean
    fun accountApi(): GroupedOpenApi = group("auth-user", "com.cowork.auth", "com.cowork.user")

    @Bean
    fun teamApi(): GroupedOpenApi = group("team-project", "com.cowork.team", "com.cowork.project")

    @Bean
    fun channelApi(): GroupedOpenApi = group("channel-chat", "com.cowork.channel", "com.cowork.chat")

    @Bean
    fun realtimeApi(): GroupedOpenApi =
        group("preference-notification-voice", "com.cowork.preference", "com.cowork.notification", "com.cowork.voice")

    @Bean
    fun adminApi(): GroupedOpenApi = group("admin", "com.cowork.admin")

    private fun group(name: String, vararg packages: String): GroupedOpenApi =
        GroupedOpenApi.builder().group(name).packagesToScan(*packages).build()
}
