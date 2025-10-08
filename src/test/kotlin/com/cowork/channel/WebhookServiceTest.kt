package com.cowork.channel

import com.cowork.channel.domain.Channel
import com.cowork.channel.domain.ChannelViewType
import com.cowork.channel.domain.Webhook
import com.cowork.channel.dto.CreateWebhookRequest
import com.cowork.channel.repository.WebhookRepository
import com.cowork.channel.service.ChannelAccessChecker
import com.cowork.channel.service.WebhookService
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WebhookServiceTest {

    private val webhookRepository = mockk<WebhookRepository>(relaxed = true)
    private val channelAccessChecker = mockk<ChannelAccessChecker>()
    private val service = WebhookService(webhookRepository, channelAccessChecker, "https://api.cowork.team/")

    private val channel = Channel(
        teamId = 1, projectId = null, name = "알림", viewType = ChannelViewType.WEBHOOK, description = null,
        isPrivate = false, position = 0, createdBy = 2, id = 10,
    )

    private fun webhook(token: String = "secret-token") =
        Webhook(channelId = 10, name = "GitHub", avatarUrl = null, token = token, createdBy = 2, id = 3)

    @Test
    fun `웹훅을 만들면 토큰을 포함한 호출 URL을 돌려준다`() {
        val saved = slot<Webhook>()
        every { channelAccessChecker.requireManageable(10, 2) } returns channel
        every { channelAccessChecker.requireViewType(channel, ChannelViewType.TEXT, ChannelViewType.WEBHOOK) } just runs
        every { webhookRepository.save(capture(saved)) } answers { saved.captured }

        val response = service.create(10, 2, CreateWebhookRequest(name = "GitHub"))

        assertEquals("https://api.cowork.team/webhooks/0/${saved.captured.token}", response.executeUrl)
    }
}
