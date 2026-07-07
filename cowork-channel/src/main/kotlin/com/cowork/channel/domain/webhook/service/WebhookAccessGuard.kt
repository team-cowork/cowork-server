package com.cowork.channel.domain.webhook.service

import com.cowork.channel.domain.channel.entity.Channel
import com.cowork.channel.domain.channel.entity.ChannelType
import com.cowork.channel.domain.channel.service.ChannelAccessGuard
import com.cowork.channel.domain.channel.service.TeamPermissionService
import com.cowork.channel.domain.webhook.entity.Webhook
import com.cowork.channel.domain.webhook.repository.WebhookRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import team.themoment.sdk.exception.ExpectedException

@Service
class WebhookAccessGuard(
    private val webhookRepository: WebhookRepository,
    private val channelAccessGuard: ChannelAccessGuard,
    private val teamPermissionService: TeamPermissionService,
) {

    fun findWebhookOrThrow(id: Long): Webhook = webhookRepository.findById(id).orElseThrow {
        ExpectedException("웹훅을 찾을 수 없습니다.", HttpStatus.NOT_FOUND)
    }

    fun requireWebhookManager(channelId: Long, userId: Long): Channel {
        val channel = channelAccessGuard.findChannelOrThrow(channelId)
        if (channel.type != ChannelType.TEXT) {
            throw ExpectedException("TEXT 채널에서만 웹훅을 사용할 수 있습니다.", HttpStatus.BAD_REQUEST)
        }
        if (channel.createdBy != userId &&
            !teamPermissionService.isTeamOwnerOrAdmin(channelAccessGuard.requireTeamChannel(channel), userId)
        ) {
            throw ExpectedException("웹훅 관리 권한이 없습니다.", HttpStatus.FORBIDDEN)
        }
        return channel
    }
}
