package com.cowork.global.storage

import com.cowork.global.error.BusinessException
import org.springframework.http.HttpStatus

object ImageUploadPolicy {

    const val MAX_IMAGE_SIZE: Long = 5L * 1024 * 1024

    private val EXTENSIONS = mapOf(
        "image/png" to "png",
        "image/jpeg" to "jpg",
        "image/webp" to "webp",
        "image/gif" to "gif",
    )

    fun extensionOf(contentType: String): String =
        EXTENSIONS[contentType.lowercase()]
            ?: throw BusinessException(HttpStatus.BAD_REQUEST, "지원하지 않는 이미지 형식입니다.")

    fun validateSize(size: Long) {
        if (size <= 0 || size > MAX_IMAGE_SIZE) {
            throw BusinessException(HttpStatus.PAYLOAD_TOO_LARGE, "이미지 크기는 5MB 이하여야 합니다.")
        }
    }
}
