package com.cowork.notification

import com.cowork.channel.domain.Channel
import com.cowork.channel.domain.ChannelViewType
import com.cowork.channel.service.ChannelAccessChecker
import com.cowork.channel.service.ChannelAudienceResolver
import com.cowork.chat.domain.Message
import com.cowork.chat.domain.MessageType
import com.cowork.chat.dto.MessageResponse
import com.cowork.chat.event.MessageCreatedEvent
import com.cowork.notification.domain.NotificationMessage
import com.cowork.notification.domain.NotificationType
import com.cowork.notification.service.NotificationEventListener
import com.cowork.notification.service.NotificationService
import com.cowork.preference.service.PreferenceReader
import com.cowork.project.event.ProjectMemberAddedEvent
import com.cowork.team.event.TeamMemberAddedEvent
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

class NotificationEventListenerTest {

    private val notificationService = mockk<NotificationService>(relaxed = true)
    private val channelAccessChecker = mockk<ChannelAccessChecker>()
    private val audienceResolver = mockk<ChannelAudienceResolver>()
    private val preferenceReader = mockk<PreferenceReader>()
    private val listener = NotificationEventListener(notificationService, channelAccessChecker, audienceResolver, preferenceReader)

    private val channel = Channel(
        teamId = 1, projectId = null, name = "general", viewType = ChannelViewType.TEXT, description = null,
        isPrivate = false, position = 0, createdBy = 1, id = 10,
    )

    private fun created(content: String, mentioned: Set<Long> = emptySet()) = MessageCreatedEvent(
        teamId = 1,
        message = MessageResponse.of(
            Message(channelId = 10, senderId = 1, type = MessageType.TEXT, content = content, senderName = "홍길동", id = 5),
            null,
            emptyList(),
        ),
        mentionedUserIds = mentioned,
    )

    @Test
    fun `팀에 초대되면 초대받은 사용자에게 알린다`() {
        listener.onTeamMemberAdded(TeamMemberAddedEvent(1, "cowork", 7, 2))

        verify { notificationService.notify(listOf(7), match<NotificationMessage> { it.type == NotificationType.TEAM_INVITED }) }
    }

    @Test
    fun `스스로 프로젝트에 참여하면 알리지 않는다`() {
        listener.onProjectMemberAdded(ProjectMemberAddedEvent(3, "앱", 7, 7))

        verify(exactly = 0) { notificationService.notify(any(), any()) }
    }

    @Test
    fun `보낸 사람은 메시지 알림을 받지 않는다`() {
        every { channelAccessChecker.findChannel(10) } returns channel
        every { audienceResolver.resolve(channel) } returns setOf(1, 2, 3)
        every { preferenceReader.filterNotifiable(10, setOf(2L, 3L)) } returns setOf(2, 3)

        listener.onMessageCreated(created("안녕하세요"))

        verify { notificationService.notify(setOf(2L, 3L), match<NotificationMessage> { it.body == "홍길동: 안녕하세요" }) }
    }

    @Test
    fun `멘션된 사용자는 멘션 알림만 받는다`() {
        every { channelAccessChecker.findChannel(10) } returns channel
        every { audienceResolver.resolve(channel) } returns setOf(1, 2, 3)
        every { preferenceReader.filterNotifiable(10, setOf(2L, 3L)) } returns setOf(2, 3)

        listener.onMessageCreated(created("<@2> 확인해 주세요", mentioned = setOf(2)))

        verify { notificationService.notify(setOf(2L), match<NotificationMessage> { it.type == NotificationType.CHAT_MENTION }) }
        verify { notificationService.notify(setOf(3L), match<NotificationMessage> { it.type == NotificationType.CHAT_MESSAGE }) }
    }

    @Test
    fun `파일만 보낸 메시지는 미리보기 문구로 알린다`() {
        every { channelAccessChecker.findChannel(10) } returns channel
        every { audienceResolver.resolve(channel) } returns setOf(1, 2)
        every { preferenceReader.filterNotifiable(10, setOf(2L)) } returns setOf(2)

        listener.onMessageCreated(created(""))

        verify { notificationService.notify(setOf(2L), match<NotificationMessage> { it.body.endsWith("파일을 보냈습니다.") }) }
    }

    @Test
    fun `채널 조회에 실패해도 예외를 밖으로 던지지 않는다`() {
        every { channelAccessChecker.findChannel(10) } throws IllegalStateException("db down")

        listener.onMessageCreated(created("hello"))

        verify(exactly = 0) { notificationService.notify(any(), any()) }
    }
}
