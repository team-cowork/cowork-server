package com.cowork.global.storage

import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.stereotype.Component
import software.amazon.awssdk.core.exception.SdkException
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.HeadBucketRequest

/**
 * 파일 버킷에 접근할 수 있는지 확인한다. 배포 헬스 체크에서 스토리지 설정 오류를 바로 알 수 있게 한다.
 */
@Component("storage")
class StorageHealthIndicator(
    private val s3Client: S3Client,
    private val properties: StorageProperties,
) : HealthIndicator {

    override fun health(): Health = try {
        s3Client.headBucket(HeadBucketRequest.builder().bucket(properties.bucket).build())
        Health.up().withDetail("bucket", properties.bucket).build()
    } catch (e: SdkException) {
        Health.down(e).withDetail("bucket", properties.bucket).build()
    }
}
