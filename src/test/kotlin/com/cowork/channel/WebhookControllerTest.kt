package com.cowork.channel

import com.cowork.channel.controller.WebhookController
import com.cowork.channel.domain.Webhook
import com.cowork.channel.dto.WebhookResponse
import com.cowork.channel.service.WebhookService
import com.cowork.support.ControllerTestSupport
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.post

class WebhookControllerTest : ControllerTestSupport() {

    private val webhookService = mockk<WebhookService>()
    private val mockMvc = mockMvc(WebhookController(webhookService))

    @Test
    fun `웹훅을 만들면 호출 URL을 함께 반환한다`() {
        val webhook = Webhook(channelId = 10, name = "GitHub", avatarUrl = null, token = "token", createdBy = 1, id = 3)
        every { webhookService.create(10, 1, any()) } returns WebhookResponse.of(webhook, "https://api.cowork.team")

        mockMvc.post("/channels/10/webhooks") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"GitHub"}"""
        }.andExpect {
            status { isCreated() }
            jsonPath("$.executeUrl") { value("https://api.cowork.team/webhooks/3/token") }
        }
    }

    @Test
    fun `웹훅을 삭제하면 204를 반환한다`() {
        every { webhookService.delete(10, 3, 1) } just runs

        mockMvc.delete("/channels/10/webhooks/3").andExpect { status { isNoContent() } }
    }
}
