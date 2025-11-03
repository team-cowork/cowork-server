package com.cowork.chat

import com.cowork.chat.controller.WebhookExecutionController
import com.cowork.chat.service.WebhookMessageService
import com.cowork.global.error.BusinessException
import com.cowork.support.ControllerTestSupport
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.post

class WebhookExecutionControllerTest : ControllerTestSupport() {

    private val webhookMessageService = mockk<WebhookMessageService>()
    private val mockMvc = mockMvc(WebhookExecutionController(webhookMessageService))

    @Test
    fun `내용이 없는 웹훅 요청은 400을 반환한다`() {
        mockMvc.post("/webhooks/3/token") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"username":"CI"}"""
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `토큰이 틀리면 401을 반환한다`() {
        every { webhookMessageService.execute(3, "wrong", "배포 완료", null) } throws
            BusinessException(HttpStatus.UNAUTHORIZED, "유효하지 않은 웹훅입니다.")

        mockMvc.post("/webhooks/3/wrong") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"content":"배포 완료"}"""
        }.andExpect { status { isUnauthorized() } }
    }
}
