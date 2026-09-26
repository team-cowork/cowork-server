package com.cowork.gateway.logging.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties(prefix = "sdk.logging")
class SdkLoggingProperties {
    var notLoggingUrls: List<String> = emptyList()
}
