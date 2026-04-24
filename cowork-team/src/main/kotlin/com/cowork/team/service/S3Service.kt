package com.cowork.team.service

import com.cowork.team.config.MinioProperties
import io.awspring.cloud.s3.S3Template
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import team.themoment.sdk.exception.ExpectedException
import java.time.Duration
import java.util.UUID

@Service
class S3Service(
    private val s3Template: S3Template,
    private val s3Presigner: S3Presigner,
    private val s3Client: S3Client,
    private val minioProperties: MinioProperties,
) {

    fun validateContentType(contentType: String) {
        if (contentType !in minioProperties.allowedContentTypes) {
            throw ExpectedException(
                "허용되지 않는 파일 형식입니다. 허용 형식: ${minioProperties.allowedContentTypes.joinToString()}",
                HttpStatus.BAD_REQUEST,
            )
        }
    }

    fun buildObjectKey(contentType: String): String {
        val ext = when (contentType) {
            "image/jpeg" -> "jpg"
            "image/png"  -> "png"
            "image/webp" -> "webp"
            else         -> throw ExpectedException("허용되지 않는 파일 형식입니다.", HttpStatus.BAD_REQUEST)
        }
        return "team-icons/${UUID.randomUUID()}.$ext"
    }

    fun generatePutPresignedUrl(objectKey: String, contentType: String): String {
        val putObjectRequest = PutObjectRequest.builder()
            .bucket(minioProperties.bucket)
            .key(objectKey)
            .contentType(contentType)
            .build()

        val presignRequest = PutObjectPresignRequest.builder()
            .signatureDuration(Duration.ofMinutes(minioProperties.presignedPutExpiryMinutes))
            .putObjectRequest(putObjectRequest)
            .build()

        return s3Presigner.presignPutObject(presignRequest).url().toString()
    }

    fun confirmObject(objectKey: String): String {
        if (!objectKey.startsWith("team-icons/")) {
            throw ExpectedException("유효하지 않은 objectKey입니다.", HttpStatus.BAD_REQUEST)
        }

        val metadata = try {
            s3Client.headObject { it.bucket(minioProperties.bucket).key(objectKey) }
        } catch (e: NoSuchKeyException) {
            throw ExpectedException("S3에 파일이 없습니다. 업로드를 먼저 완료하세요.", HttpStatus.CONFLICT)
        }

        if (metadata.contentLength() > minioProperties.maxFileSizeBytes) {
            s3Template.deleteObject(minioProperties.bucket, objectKey)
            throw ExpectedException("파일 크기가 1MB를 초과합니다.", HttpStatus.PAYLOAD_TOO_LARGE)
        }

        return "${minioProperties.publicBaseUrl}/$objectKey"
    }

    fun extractObjectKey(iconUrl: String): String =
        iconUrl.removePrefix("${minioProperties.publicBaseUrl}/")

    fun deleteObject(objectKey: String) {
        s3Template.deleteObject(minioProperties.bucket, objectKey)
    }
}
