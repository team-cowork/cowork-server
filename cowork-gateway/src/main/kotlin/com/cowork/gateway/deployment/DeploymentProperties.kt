package com.cowork.gateway.deployment

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("gateway.deployment")
data class DeploymentProperties(val destinations: Map<String, String> = emptyMap())
