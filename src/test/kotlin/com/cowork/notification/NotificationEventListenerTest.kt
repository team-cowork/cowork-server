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
}
