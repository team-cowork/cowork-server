package com.cowork.user.service

import com.cowork.user.config.MinioProperties
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

    fun buildObjectKey(userId: Long, contentType: String): String {
        val ext = contentType.substringAfterLast("/")
        return "profiles/$userId/${UUID.randomUUID()}.$ext"
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

    fun generateGetPresignedUrl(objectKey: String): String =
        s3Template.createSignedGetURL(
            minioProperties.bucket,
            objectKey,
            Duration.ofMinutes(minioProperties.presignedGetExpiryMinutes),
        ).toString()

    fun verifyUpload(userId: Long, objectKey: String) {
        if (!objectKey.startsWith("profiles/$userId/")) {
            throw ExpectedException("유효하지 않은 objectKey입니다.", HttpStatus.BAD_REQUEST)
        }

        val metadata = try {
            s3Client.headObject { it.bucket(minioProperties.bucket).key(objectKey) }
        } catch (e: NoSuchKeyException) {
            throw ExpectedException("S3에 파일이 없습니다. 업로드를 먼저 완료하세요.", HttpStatus.CONFLICT)
        }

        if (metadata.contentLength() > minioProperties.maxFileSizeBytes) {
            s3Template.deleteObject(minioProperties.bucket, objectKey)
            throw ExpectedException("파일 크기가 허용 한도를 초과합니다.", HttpStatus.PAYLOAD_TOO_LARGE)
        }
    }

    fun deleteObject(objectKey: String) {
        s3Template.deleteObject(minioProperties.bucket, objectKey)
    }
}
