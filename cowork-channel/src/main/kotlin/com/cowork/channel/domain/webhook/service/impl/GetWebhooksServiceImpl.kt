package com.cowork.channel.domain.webhook.service.impl

import com.cowork.channel.domain.channel.service.ChannelAccessGuard
import com.cowork.channel.domain.channel.service.TeamPermissionService
import com.cowork.channel.domain.webhook.presentation.data.response.WebhookResponse
import com.cowork.channel.domain.webhook.repository.WebhookRepository
import com.cowork.channel.domain.webhook.service.GetWebhooksService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class GetWebhooksServiceImpl(
    private val webhookRepository: WebhookRepository,
    private val channelAccessGuard: ChannelAccessGuard,
    private val teamPermissionService: TeamPermissionService,
) : GetWebhooksService {

    override fun getWebhooks(userId: Long, channelId: Long): List<WebhookResponse> {
        val channel = channelAccessGuard.findChannelOrThrow(channelId)
        teamPermissionService.requireTeamMember(channelAccessGuard.requireTeamChannel(channel), userId)
        return webhookRepository.findByChannelId(channelId).map { WebhookResponse.of(it) }
    }
}
