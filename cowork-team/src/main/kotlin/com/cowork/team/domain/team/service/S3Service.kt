package com.cowork.team.domain.team.service

import com.cowork.team.global.config.ObjectStorageProperties
import io.awspring.cloud.s3.S3Template
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.HeadObjectResponse
import software.amazon.awssdk.services.s3.model.MetadataDirective
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.S3Exception
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

        val signed = s3Presigner.presignPutObject(presignRequest).url().toURI()
        // Gateway가 접두사를 제거하고 서명에 사용한 API Host로 전달한다.
        val apiBaseUrl = objectStorageProperties.publicApiBaseUrl.trimEnd('/')
        return "$apiBaseUrl/api/storage${signed.rawPath}?${signed.rawQuery}"
    }

    fun confirmObject(objectKey: String, expectedContentType: String? = null): String {
        if (!isManagedObjectKey(objectKey)) {
            throw ExpectedException("유효하지 않은 objectKey입니다.", HttpStatus.BAD_REQUEST)
        }

        verifyObject(objectKey, expectedContentType)
        return buildPublicUrl(objectKey)
    }

    private fun verifyObject(objectKey: String, expectedContentType: String?): HeadObjectResponse {
        val metadata = try {
            s3Client.headObject { it.bucket(objectStorageProperties.bucket).key(objectKey) }
        } catch (exception: S3Exception) {
            if (exception.statusCode() == 404) {
                throw ExpectedException("S3에 파일이 없습니다. 업로드를 먼저 완료하세요.", HttpStatus.CONFLICT)
            }
            throw exception
        }

        val contentLength = metadata.contentLength()
        if (contentLength == null || contentLength <= 0) {
            throw ExpectedException("파일 크기 메타데이터가 유효하지 않습니다.", HttpStatus.BAD_REQUEST)
        }
        if (contentLength > objectStorageProperties.maxFileSizeBytes) {
            s3Template.deleteObject(objectStorageProperties.bucket, objectKey)
            throw ExpectedException("파일 크기가 1MB를 초과합니다.", HttpStatus.CONTENT_TOO_LARGE)
        }

        validateContentType(metadata.contentType().orEmpty())
        if (expectedContentType != null && metadata.contentType() != expectedContentType) {
            s3Template.deleteObject(objectStorageProperties.bucket, objectKey)
            throw ExpectedException("발급한 파일 형식과 업로드한 파일 형식이 다릅니다.", HttpStatus.BAD_REQUEST)
        }
        return metadata
    }

    fun finalizeUpload(uploadKey: String, objectKey: String, contentType: String) {
        if (!isManagedObjectKey(objectKey) ||
            !Regex("team-icon-uploads/[1-9][0-9]*/[0-9a-f-]{36}\\.(jpg|png|webp)").matches(uploadKey)
        ) {
            throw ExpectedException("유효하지 않은 아이콘 경로입니다.", HttpStatus.BAD_REQUEST)
        }
        val source = verifyObject(uploadKey, contentType)
        val etag = source.eTag()?.takeIf { it.isNotBlank() }
            ?: throw ExpectedException("파일 무결성 메타데이터가 유효하지 않습니다.", HttpStatus.BAD_REQUEST)
        try {
            // 브라우저의 PUT 권한은 임시 객체에만 있다. 확정된 아이콘은 서버가 복사한다.
            s3Client.copyObject {
                it.destinationBucket(objectStorageProperties.bucket).destinationKey(objectKey)
                    .sourceBucket(objectStorageProperties.bucket).sourceKey(uploadKey)
                    .copySourceIfMatch(etag).metadataDirective(MetadataDirective.REPLACE).contentType(contentType)
            }
        } catch (exception: S3Exception) {
            if (exception.statusCode() == 404 || exception.statusCode() == 412) {
                throw ExpectedException("업로드한 파일을 다시 확인해야 합니다.", HttpStatus.CONFLICT)
            }
            throw exception
        }
        confirmObject(objectKey, contentType)
        s3Client.deleteObject { it.bucket(objectStorageProperties.bucket).key(uploadKey) }
    }

    fun buildPublicUrl(objectKey: String): String = "${objectStorageProperties.publicBaseUrl.trimEnd('/')}/$objectKey"

    fun validateIconUrl(iconUrl: String) {
        extractObjectKey(iconUrl)
    }

    fun managedObjectKeyOrNull(iconUrl: String): String? {
        val prefix = "${objectStorageProperties.publicBaseUrl.trimEnd('/')}/"
        if (!iconUrl.startsWith(prefix)) return null
        return iconUrl.removePrefix(prefix).takeIf(::isManagedObjectKey)
    }

    fun extractObjectKey(iconUrl: String): String = managedObjectKeyOrNull(iconUrl)
        ?: throw ExpectedException("유효하지 않은 아이콘 URL입니다.", HttpStatus.BAD_REQUEST)

    fun deleteObject(objectKey: String) {
        if (!isManagedObjectKey(objectKey)) {
            throw ExpectedException("유효하지 않은 아이콘 경로입니다.", HttpStatus.BAD_REQUEST)
        }
        s3Template.deleteObject(objectStorageProperties.bucket, objectKey)
    }

    private fun isManagedObjectKey(objectKey: String): Boolean = Regex(
        "team-icons/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(jpg|png|webp)",
    ).matches(objectKey)
}
