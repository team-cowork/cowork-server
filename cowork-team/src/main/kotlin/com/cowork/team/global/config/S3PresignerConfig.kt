package com.cowork.team.global.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.core.env.Environment
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import java.net.URI

@Configuration
class S3PresignerConfig(private val env: Environment) {

    @Bean
    @Primary
    fun s3Presigner(): S3Presigner {
        val apiEndpoint = env.getRequired("object-storage.public-api-base-url")
        val region = env.getProperty("spring.cloud.aws.region.static") ?: "ap-northeast-2"
        val accessKey = env.getProperty("spring.cloud.aws.credentials.access-key").orEmpty()
        val secretKey = env.getProperty("spring.cloud.aws.credentials.secret-key").orEmpty()
        val endpoint = URI.create(apiEndpoint)
        require(
            endpoint.scheme in setOf("http", "https") && !endpoint.host.isNullOrBlank() &&
                endpoint.path in setOf("", "/") && endpoint.rawUserInfo == null &&
                endpoint.rawQuery == null && endpoint.rawFragment == null,
        ) {
            "Public API endpoint must be an HTTP(S) origin"
        }
        require(accessKey.isNotBlank() && secretKey.isNotBlank()) { "S3 service credentials are required" }
        if ("prod" in env.activeProfiles) {
            require(endpoint.scheme == "https" && endpoint.host !in setOf("localhost", "127.0.0.1", "[::1]")) {
                "Production API endpoint requires HTTPS and a non-local host"
            }
        }

        return S3Presigner.builder()
            .endpointOverride(endpoint)
            .region(Region.of(region))
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(accessKey, secretKey),
                ),
            )
            .serviceConfiguration(
                S3Configuration.builder()
                    .pathStyleAccessEnabled(true)
                    .build(),
            )
            .build()
    }

    private fun Environment.getRequired(key: String): String = getProperty(key)?.takeIf { it.isNotBlank() }
        ?: throw IllegalStateException("Missing required property: $key")
}
