package com.cowork.notification

import com.cowork.notification.controller.NotificationController
import com.cowork.notification.domain.DevicePlatform
import com.cowork.notification.service.DeviceTokenService
import com.cowork.notification.service.SseEmitterRegistry
import com.cowork.support.ControllerTestSupport
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.post

class NotificationControllerTest : ControllerTestSupport() {

    private val deviceTokenService = mockk<DeviceTokenService>()
    private val mockMvc = mockMvc(NotificationController(deviceTokenService, SseEmitterRegistry()))

    @Test
    fun `기기 토큰을 등록하면 204를 반환한다`() {
        every { deviceTokenService.register(1, "fcm-token", DevicePlatform.IOS) } just runs

        mockMvc.post("/notifications/tokens") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"token":"fcm-token","platform":"IOS"}"""
        }.andExpect { status { isNoContent() } }

        verify { deviceTokenService.register(1, "fcm-token", DevicePlatform.IOS) }
    }

    @Test
    fun `플랫폼 없이 토큰을 등록할 수 없다`() {
        mockMvc.post("/notifications/tokens") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"token":"fcm-token"}"""
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `기기 토큰을 삭제하면 204를 반환한다`() {
        every { deviceTokenService.delete(1, "fcm-token") } just runs

        mockMvc.delete("/notifications/tokens/fcm-token").andExpect { status { isNoContent() } }
    }
}
