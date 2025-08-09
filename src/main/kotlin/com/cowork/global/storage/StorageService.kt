package com.cowork.global.storage

import org.springframework.stereotype.Service
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.S3Exception
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest

@Service
class StorageService(
    private val s3Client: S3Client,
    private val s3Presigner: S3Presigner,
    private val properties: StorageProperties,
) {

    fun createUploadUrl(objectKey: String, contentType: String): String {
        val putRequest = PutObjectRequest.builder()
            .bucket(properties.bucket)
            .key(objectKey)
            .contentType(contentType)
            .build()
        val presignRequest = PutObjectPresignRequest.builder()
            .signatureDuration(properties.presignedUrlTtl)
            .putObjectRequest(putRequest)
            .build()
        return s3Presigner.presignPutObject(presignRequest).url().toString()
    }

    fun createDownloadUrl(objectKey: String): String {
        val getRequest = GetObjectRequest.builder()
            .bucket(properties.bucket)
            .key(objectKey)
            .build()
        val presignRequest = GetObjectPresignRequest.builder()
            .signatureDuration(properties.presignedUrlTtl)
            .getObjectRequest(getRequest)
            .build()
        return s3Presigner.presignGetObject(presignRequest).url().toString()
    }

    fun findObjectSize(objectKey: String): Long? = try {
        s3Client.headObject(
            HeadObjectRequest.builder().bucket(properties.bucket).key(objectKey).build(),
        ).contentLength()
    } catch (e: NoSuchKeyException) {
        null
    } catch (e: S3Exception) {
        if (e.statusCode() == 404) null else throw e
    }

    fun delete(objectKey: String) {
        s3Client.deleteObject(
            DeleteObjectRequest.builder().bucket(properties.bucket).key(objectKey).build(),
        )
    }
}
