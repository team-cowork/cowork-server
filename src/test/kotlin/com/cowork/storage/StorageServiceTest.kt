package com.cowork.storage

import com.cowork.global.storage.StorageProperties
import com.cowork.global.storage.StorageService
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest
import java.net.URI
import java.time.Duration

class StorageServiceTest {

    private val s3Client = mockk<S3Client>(relaxed = true)
    private val s3Presigner = mockk<S3Presigner>()
    private val properties = StorageProperties(
        endpoint = null,
        publicEndpoint = null,
        bucket = "cowork",
        accessKey = null,
        secretKey = null,
        presignedUrlTtl = Duration.ofMinutes(5),
    )
    private val service = StorageService(s3Client, s3Presigner, properties)

    @Test
    fun `업로드 URL은 설정한 버킷과 만료 시간으로 서명한다`() {
        val request = slot<PutObjectPresignRequest>()
        val presigned = mockk<PresignedPutObjectRequest>()
        every { presigned.url() } returns URI("https://storage/cowork/a.png").toURL()
        every { s3Presigner.presignPutObject(capture(request)) } returns presigned

        val url = service.createUploadUrl("a.png", "image/png")

        assertEquals("https://storage/cowork/a.png", url)
        assertEquals("cowork", request.captured.putObjectRequest().bucket())
        assertEquals(Duration.ofMinutes(5), request.captured.signatureDuration())
    }
}
