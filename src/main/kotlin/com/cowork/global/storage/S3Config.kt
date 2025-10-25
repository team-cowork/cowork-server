package com.cowork.global.storage

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import java.net.URI

@Configuration
class S3Config(
    private val properties: StorageProperties,
) {

    @Bean
    fun s3Client(): S3Client {
        val builder = S3Client.builder()
            .region(Region.of(properties.region))
            .credentialsProvider(credentialsProvider())
            .serviceConfiguration(serviceConfiguration())
        properties.endpoint?.takeIf { it.isNotBlank() }?.let { builder.endpointOverride(URI.create(it)) }
        return builder.build()
    }

    @Bean
    fun s3Presigner(): S3Presigner {
        val builder = S3Presigner.builder()
            .region(Region.of(properties.region))
            .credentialsProvider(credentialsProvider())
            .serviceConfiguration(serviceConfiguration())
        val endpoint = properties.publicEndpoint?.takeIf { it.isNotBlank() } ?: properties.endpoint
        endpoint?.takeIf { it.isNotBlank() }?.let { builder.endpointOverride(URI.create(it)) }
        return builder.build()
    }

    private fun serviceConfiguration(): S3Configuration =
        S3Configuration.builder().pathStyleAccessEnabled(properties.pathStyleAccess).build()

    private fun credentialsProvider(): AwsCredentialsProvider {
        val accessKey = properties.accessKey
        val secretKey = properties.secretKey
        return if (!accessKey.isNullOrBlank() && !secretKey.isNullOrBlank()) {
            StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey))
        } else {
            DefaultCredentialsProvider.builder().build()
        }
    }
}
