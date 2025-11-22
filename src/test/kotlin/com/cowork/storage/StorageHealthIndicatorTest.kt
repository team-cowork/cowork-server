package com.cowork.storage

import com.cowork.global.storage.StorageHealthIndicator
import com.cowork.global.storage.StorageProperties
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.boot.actuate.health.Status
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.HeadBucketRequest
import software.amazon.awssdk.services.s3.model.HeadBucketResponse
import software.amazon.awssdk.services.s3.model.NoSuchBucketException

class StorageHealthIndicatorTest {

    private val s3Client = mockk<S3Client>()
    private val indicator = StorageHealthIndicator(
        s3Client,
        StorageProperties(endpoint = null, publicEndpoint = null, bucket = "cowork", accessKey = null, secretKey = null),
    )

    @Test
    fun `버킷에 접근할 수 있으면 UP이다`() {
        every { s3Client.headBucket(any<HeadBucketRequest>()) } returns HeadBucketResponse.builder().build()

        assertEquals(Status.UP, indicator.health().status)
    }

    @Test
    fun `버킷이 없으면 DOWN이다`() {
        every { s3Client.headBucket(any<HeadBucketRequest>()) } throws NoSuchBucketException.builder().build()

        assertEquals(Status.DOWN, indicator.health().status)
    }
}
