package com.cowork.storage

import com.cowork.global.error.BusinessException
import com.cowork.global.storage.ImageUploadPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus

class ImageUploadPolicyTest {

    @Test
    fun `지원하는 이미지 형식은 확장자로 변환한다`() {
        assertEquals("png", ImageUploadPolicy.extensionOf("image/png"))
        assertEquals("jpg", ImageUploadPolicy.extensionOf("IMAGE/JPEG"))
    }

    @Test
    fun `지원하지 않는 형식은 거부한다`() {
        val exception = assertThrows<BusinessException> { ImageUploadPolicy.extensionOf("image/svg+xml") }

        assertEquals(HttpStatus.BAD_REQUEST, exception.status)
    }
}
