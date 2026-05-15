package com.cowork.channel.service

import com.cowork.channel.domain.Channel
import com.cowork.channel.domain.ChannelType
import com.cowork.channel.domain.ChannelViewType
import com.cowork.channel.dto.CreateWebhookRequest
import com.cowork.channel.repository.WebhookRepository
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException

class WebhookServiceTest {

    private val webhookRepository = mockk<WebhookRepository>(relaxed = true)
    private val channelService = mockk<ChannelService>()
    private val teamPermission = mockk<TeamPermissionService>()

    private val service = WebhookService(webhookRepository, channelService, teamPermission)

    private fun channel(type: ChannelType, view: ChannelViewType = ChannelViewType.TEXT, createdBy: Long = 1L) = Channel(
        id = 1L, teamId = 100L, name = "ch", type = type, viewType = view,
        description = null, isPrivate = false, createdBy = createdBy,
    )

    @Test
    fun `VOICE 채널에서는 웹훅 생성 시 BAD_REQUEST`() {
        every { channelService.findChannelOrThrow(1L) } returns channel(ChannelType.VOICE)

        val ex = assertThrows(ExpectedException::class.java) {
            service.createWebhook(1L, 1L, CreateWebhookRequest(name = "w"))
        }
        assertEquals(HttpStatus.BAD_REQUEST, ex.statusCode)
    }

    @Test
    fun `TEXT 채널에서는 view_type 무관하게 웹훅 생성 가능`() {
        every { channelService.findChannelOrThrow(1L) } returns
            channel(ChannelType.TEXT, view = ChannelViewType.MEETING_NOTE, createdBy = 1L)
        every { teamPermission.isTeamOwnerOrAdmin(any(), any()) } returns false
        every { webhookRepository.save(any()) } answers { firstArg() }

        val resp = service.createWebhook(1L, 1L, CreateWebhookRequest(name = "w", isSecure = true))
        assertEquals("w", resp.name)
        assertEquals(true, resp.isSecure)
    }
}
