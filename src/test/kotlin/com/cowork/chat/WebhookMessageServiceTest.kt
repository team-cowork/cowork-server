package com.cowork.chat

import com.cowork.channel.domain.Channel
import com.cowork.channel.domain.ChannelViewType
import com.cowork.channel.domain.Webhook
import com.cowork.channel.service.ChannelAccessChecker
import com.cowork.channel.service.WebhookService
import com.cowork.chat.domain.Message
import com.cowork.chat.repository.MessageRepository
import com.cowork.chat.service.MessageAssembler
import com.cowork.chat.service.WebhookMessageService
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher

class WebhookMessageServiceTest {

    private val webhookService = mockk<WebhookService>()
    private val channelAccessChecker = mockk<ChannelAccessChecker>()
    private val messageRepository = mockk<MessageRepository>()
    private val messageAssembler = mockk<MessageAssembler>(relaxed = true)
    private val eventPublisher = mockk<ApplicationEventPublisher>(relaxed = true)
    private val service = WebhookMessageService(
        webhookService, channelAccessChecker, messageRepository, messageAssembler, eventPublisher,
    )

    private val saved = slot<Message>()

    @BeforeEach
    fun setUp() {
        every { webhookService.authenticate(3, "token") } returns
            Webhook(channelId = 10, name = "GitHub", avatarUrl = null, token = "token", createdBy = 1, id = 3)
        every { channelAccessChecker.findChannel(10) } returns Channel(
            teamId = 1, projectId = null, name = "ci", viewType = ChannelViewType.WEBHOOK, description = null,
            isPrivate = false, position = 0, createdBy = 1, id = 10,
        )
        every { messageRepository.save(capture(saved)) } answers { saved.captured }
    }

    @Test
    fun `웹훅 메시지는 보낸 사용자 없이 웹훅 이름으로 저장한다`() {
        service.execute(3, "token", "  빌드 성공  ", null)

        assertNull(saved.captured.senderId)
        assertEquals("GitHub", saved.captured.senderName)
        assertEquals("빌드 성공", saved.captured.content)
        assertEquals(3, saved.captured.webhookId)
    }

    @Test
    fun `요청에 이름이 있으면 100자까지 보낸 이름으로 쓴다`() {
        service.execute(3, "token", "배포", "a".repeat(120))

        assertEquals(100, saved.captured.senderName?.length)
    }
}
