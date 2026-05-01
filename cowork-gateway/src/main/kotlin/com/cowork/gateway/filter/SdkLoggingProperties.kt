package com.cowork.gateway.filter

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties(prefix = "sdk.logging")
class SdkLoggingProperties {
    var notLoggingUrls: List<String> = emptyList()
}
