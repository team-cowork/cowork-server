package com.cowork.user.config

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
class MinioPresignerConfig(
    private val env: Environment,
) {

    /**
     * 내부 통신용 MinIO endpoint(예: 내부 IP)와
     * 외부/앱이 접근 가능한 public endpoint(예: 도메인)가 다를 수 있어서,
     * presigned URL은 public endpoint로 서명하도록 Presigner만 분리한다.
     */
    @Bean
    @Primary
    fun publicS3Presigner(): S3Presigner {
        val publicEndpoint = env.getRequired("minio.public-endpoint")
        val region = env.getProperty("spring.cloud.aws.region.static") ?: "ap-northeast-2"
        val accessKey = env.getProperty("spring.cloud.aws.credentials.access-key").orEmpty()
        val secretKey = env.getProperty("spring.cloud.aws.credentials.secret-key").orEmpty()
        val pathStyle = env.getProperty("spring.cloud.aws.s3.path-style-access-enabled", Boolean::class.java, true)

        return S3Presigner.builder()
            .endpointOverride(URI.create(publicEndpoint))
            .region(Region.of(region))
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(accessKey, secretKey),
                ),
            )
            .serviceConfiguration(
                S3Configuration.builder()
                    .pathStyleAccessEnabled(pathStyle)
                    .build(),
            )
            .build()
    }

    private fun Environment.getRequired(key: String): String =
        getProperty(key)?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Missing required property: $key")
}
