package com.cowork.team.domain.team.service

import com.cowork.team.global.config.ObjectStorageProperties
import io.awspring.cloud.s3.S3Template
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest
import team.themoment.sdk.exception.ExpectedException
import java.time.Duration
import java.util.UUID

@Service
class S3Service(
    private val s3Template: S3Template,
    private val s3Presigner: S3Presigner,
    private val s3Client: S3Client,
    private val objectStorageProperties: ObjectStorageProperties,
) {

    fun validateContentType(contentType: String) {
        if (contentType !in objectStorageProperties.allowedContentTypes) {
            throw ExpectedException("허용되지 않는 파일 형식입니다.", HttpStatus.BAD_REQUEST)
        }
    }

    fun buildObjectKey(contentType: String): String {
        val ext = when (contentType) {
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            "image/webp" -> "webp"
            else -> throw ExpectedException("허용되지 않는 파일 형식입니다.", HttpStatus.BAD_REQUEST)
        }
        return "team-icons/${UUID.randomUUID()}.$ext"
    }

    fun generatePutPresignedUrl(objectKey: String, contentType: String): String {
        val putObjectRequest = PutObjectRequest.builder()
            .bucket(objectStorageProperties.bucket)
            .key(objectKey)
            .contentType(contentType)
            .build()

        val presignRequest = PutObjectPresignRequest.builder()
            .signatureDuration(Duration.ofMinutes(objectStorageProperties.presignedPutExpiryMinutes))
            .putObjectRequest(putObjectRequest)
            .build()

        return s3Presigner.presignPutObject(presignRequest).url().toString()
    }

    fun confirmObject(objectKey: String): String {
        if (!objectKey.startsWith("team-icons/")) {
            throw ExpectedException("유효하지 않은 objectKey입니다.", HttpStatus.BAD_REQUEST)
        }

        val metadata = try {
            s3Client.headObject { it.bucket(objectStorageProperties.bucket).key(objectKey) }
        } catch (e: NoSuchKeyException) {
            throw ExpectedException("S3에 파일이 없습니다. 업로드를 먼저 완료하세요.", HttpStatus.CONFLICT)
        }

        if (metadata.contentLength() > objectStorageProperties.maxFileSizeBytes) {
            s3Template.deleteObject(objectStorageProperties.bucket, objectKey)
            throw ExpectedException("파일 크기가 1MB를 초과합니다.", HttpStatus.CONTENT_TOO_LARGE)
        }

        return "${objectStorageProperties.publicBaseUrl}/$objectKey"
    }

    fun validateIconUrl(iconUrl: String) {
        if (!iconUrl.startsWith("${objectStorageProperties.publicBaseUrl}/")) {
            throw ExpectedException("유효하지 않은 아이콘 URL입니다.", HttpStatus.BAD_REQUEST)
        }
    }

    fun extractObjectKey(iconUrl: String): String = iconUrl.removePrefix("${objectStorageProperties.publicBaseUrl}/")

    fun deleteObject(objectKey: String) {
        s3Template.deleteObject(objectStorageProperties.bucket, objectKey)
    }
}
