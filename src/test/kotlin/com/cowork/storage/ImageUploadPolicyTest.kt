package com.cowork.storage

import com.cowork.global.error.BusinessException
import com.cowork.global.storage.ImageUploadPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
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

    @Test
    fun `5MB까지는 허용한다`() {
        assertDoesNotThrow { ImageUploadPolicy.validateSize(ImageUploadPolicy.MAX_IMAGE_SIZE) }
    }

    @Test
    fun `5MB를 넘거나 0 이하인 크기는 거부한다`() {
        assertThrows<BusinessException> { ImageUploadPolicy.validateSize(ImageUploadPolicy.MAX_IMAGE_SIZE + 1) }
        assertThrows<BusinessException> { ImageUploadPolicy.validateSize(0) }
    }
}
