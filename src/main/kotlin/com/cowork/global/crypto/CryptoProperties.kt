package com.cowork.global.crypto

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "cowork.crypto")
data class CryptoProperties(
    /** Base64로 인코딩한 32바이트 AES 키 */
    val credentialKey: String,
)
