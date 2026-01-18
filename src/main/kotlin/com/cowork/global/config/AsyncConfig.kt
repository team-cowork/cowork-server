package com.cowork.global.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor

@Configuration
@EnableAsync
@EnableScheduling
class AsyncConfig {

    @Bean(name = ["taskExecutor"])
    fun taskExecutor(): ThreadPoolTaskExecutor = ThreadPoolTaskExecutor().apply {
        corePoolSize = 4
        maxPoolSize = 16
        queueCapacity = 500
        setThreadNamePrefix("cowork-async-")
        setWaitForTasksToCompleteOnShutdown(true)
        setAwaitTerminationSeconds(20)
    }
}
