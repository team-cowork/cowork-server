package com.cowork.user.service

import com.cowork.user.config.S3Properties
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
    private val s3Properties: S3Properties,
) {

    fun validateContentType(contentType: String) {
        if (contentType !in s3Properties.allowedContentTypes) {
            throw ExpectedException(
                "허용되지 않는 파일 형식입니다. 허용 형식: ${s3Properties.allowedContentTypes.joinToString()}",
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
            .bucket(s3Properties.bucket)
            .key(objectKey)
            .contentType(contentType)
            .build()

        val presignRequest = PutObjectPresignRequest.builder()
            .signatureDuration(Duration.ofMinutes(s3Properties.presignedPutExpiryMinutes))
            .putObjectRequest(putObjectRequest)
            .build()

        return s3Presigner.presignPutObject(presignRequest).url().toString()
    }

    fun generateGetPresignedUrl(objectKey: String): String =
        s3Template.createSignedGetURL(
            s3Properties.bucket,
            objectKey,
            Duration.ofMinutes(s3Properties.presignedGetExpiryMinutes),
        ).toString()

    fun verifyUpload(userId: Long, objectKey: String) {
        if (!objectKey.startsWith("profiles/$userId/")) {
            throw ExpectedException("유효하지 않은 objectKey입니다.", HttpStatus.BAD_REQUEST)
        }

        val metadata = try {
            s3Client.headObject { it.bucket(s3Properties.bucket).key(objectKey) }
        } catch (e: NoSuchKeyException) {
            throw ExpectedException("S3에 파일이 없습니다. 업로드를 먼저 완료하세요.", HttpStatus.CONFLICT)
        }

        if (metadata.contentLength() > s3Properties.maxFileSizeBytes) {
            s3Template.deleteObject(s3Properties.bucket, objectKey)
            throw ExpectedException("파일 크기가 허용 한도를 초과합니다.", HttpStatus.PAYLOAD_TOO_LARGE)
        }
    }

    fun deleteObject(objectKey: String) {
        s3Template.deleteObject(s3Properties.bucket, objectKey)
    }
}
