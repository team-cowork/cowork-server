package com.cowork.user.config

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration

@EnableConfigurationProperties(MinioProperties::class)
class AppConfig
