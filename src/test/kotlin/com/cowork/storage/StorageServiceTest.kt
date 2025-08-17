package com.cowork.storage

import com.cowork.global.storage.StorageProperties
import com.cowork.global.storage.StorageService
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectResponse
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.S3Exception
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

    @Test
    fun `객체가 있으면 크기를 반환한다`() {
        every { s3Client.headObject(any<HeadObjectRequest>()) } returns
            HeadObjectResponse.builder().contentLength(1024).build()

        assertEquals(1024, service.findObjectSize("a.png"))
    }

    @Test
    fun `객체가 없으면 null을 반환한다`() {
        every { s3Client.headObject(any<HeadObjectRequest>()) } throws NoSuchKeyException.builder().build()

        assertNull(service.findObjectSize("a.png"))
    }

    @Test
    fun `404가 아닌 S3 오류는 그대로 던진다`() {
        every { s3Client.headObject(any<HeadObjectRequest>()) } throws
            S3Exception.builder().statusCode(500).build()

        assertThrows<S3Exception> { service.findObjectSize("a.png") }
    }

    @Test
    fun `삭제 요청에는 버킷과 객체 키를 담는다`() {
        val request = slot<DeleteObjectRequest>()
        every { s3Client.deleteObject(capture(request)) } returns mockk()

        service.delete("profiles/1/a.png")

        assertEquals("cowork", request.captured.bucket())
        assertEquals("profiles/1/a.png", request.captured.key())
        verify(exactly = 1) { s3Client.deleteObject(any<DeleteObjectRequest>()) }
    }
}
