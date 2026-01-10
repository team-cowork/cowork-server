package com.cowork.notification.service

import com.cowork.channel.service.ChannelAccessChecker
import com.cowork.channel.service.ChannelAudienceResolver
import com.cowork.chat.event.MessageCreatedEvent
import com.cowork.notification.domain.NotificationMessage
import com.cowork.notification.domain.NotificationType
import com.cowork.preference.service.PreferenceReader
import com.cowork.project.event.ProjectMemberAddedEvent
import com.cowork.team.event.TeamMemberAddedEvent
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionalEventListener

@Component
class NotificationEventListener(
    private val notificationService: NotificationService,
    private val channelAccessChecker: ChannelAccessChecker,
    private val channelAudienceResolver: ChannelAudienceResolver,
    private val preferenceReader: PreferenceReader,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Async
    @TransactionalEventListener
    fun onTeamMemberAdded(event: TeamMemberAddedEvent) = runSafely("team member added") {
        notificationService.notify(
            listOf(event.userId),
            NotificationMessage(
                type = NotificationType.TEAM_INVITED,
                title = "팀 초대",
                body = "'${event.teamName}' 팀에 초대되었습니다.",
                data = mapOf("teamId" to event.teamId.toString()),
            ),
        )
    }

    @Async
    @TransactionalEventListener
    fun onProjectMemberAdded(event: ProjectMemberAddedEvent) = runSafely("project member added") {
        if (event.userId == event.addedBy) return@runSafely
        notificationService.notify(
            listOf(event.userId),
            NotificationMessage(
                type = NotificationType.PROJECT_MEMBER_ADDED,
                title = "프로젝트 참여",
                body = "'${event.projectName}' 프로젝트에 추가되었습니다.",
                data = mapOf("projectId" to event.projectId.toString()),
            ),
        )
    }

    @Async
    @TransactionalEventListener
    fun onMessageCreated(event: MessageCreatedEvent) = runSafely("message created") {
        val message = event.message
        val channel = channelAccessChecker.findChannel(message.channelId)
        val audience = channelAudienceResolver.resolve(channel) - setOfNotNull(message.senderId)
        val recipients = preferenceReader.filterNotifiable(channel.id, audience)
        if (recipients.isEmpty()) return@runSafely

        val sender = message.senderName ?: "알 수 없음"
        val preview = message.content.ifBlank { "파일을 보냈습니다." }.take(PREVIEW_LENGTH)
        val data = mapOf(
            "teamId" to event.teamId.toString(),
            "channelId" to channel.id.toString(),
            "messageId" to message.id.toString(),
        )
        val mentioned = recipients intersect event.mentionedUserIds
        if (mentioned.isNotEmpty()) {
            notificationService.notify(
                mentioned,
                NotificationMessage(NotificationType.CHAT_MENTION, "#${channel.name}", "$sender 님이 회원님을 언급했습니다: $preview", data),
            )
        }
        val others = recipients - mentioned
        if (others.isNotEmpty()) {
            notificationService.notify(
                others,
                NotificationMessage(NotificationType.CHAT_MESSAGE, "#${channel.name}", "$sender: $preview", data),
            )
        }
    }

    private fun runSafely(name: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            log.warn("Fail to handle {} notification", name, e)
        }
    }

    companion object {
        private const val PREVIEW_LENGTH = 100
    }
}
