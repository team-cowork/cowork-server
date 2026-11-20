package com.cowork.channel.domain.meetingNote.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.net.http.HttpClient
import java.time.Duration

@Configuration
class MeetingNoteSummaryHttpConfig {
    @Bean
    fun meetingNoteSummaryHttpClient(): HttpClient =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
}
