package com.cowork.channel.service

import com.cowork.channel.domain.Channel
import com.cowork.channel.domain.ChannelType
import com.cowork.channel.domain.Webhook
import com.cowork.channel.dto.CreateWebhookRequest
import com.cowork.channel.dto.UpdateWebhookRequest
import com.cowork.channel.dto.WebhookResponse
import com.cowork.channel.repository.WebhookRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.themoment.sdk.exception.ExpectedException
import java.util.UUID

@Service
@Transactional(readOnly = true)
class WebhookService(
    private val webhookRepository: WebhookRepository,
    private val channelService: ChannelService,
    private val teamPermissionService: TeamPermissionService,
) {

    private fun findWebhookOrThrow(id: Long): Webhook =
        webhookRepository.findById(id).orElseThrow {
            ExpectedException("웹훅을 찾을 수 없습니다. id=$id", HttpStatus.NOT_FOUND)
        }

    private fun requireWebhookManager(channelId: Long, userId: Long, expectedTextChannel: Boolean = true): Channel {
        val channel = channelService.findChannelOrThrow(channelId)
        if (expectedTextChannel && channel.type != ChannelType.TEXT) {
            throw ExpectedException("TEXT 채널에서만 웹훅을 사용할 수 있습니다.", HttpStatus.BAD_REQUEST)
        }
        if (channel.createdBy != userId && !teamPermissionService.isTeamOwnerOrAdmin(channel.teamId, userId)) {
            throw ExpectedException("웹훅 관리 권한이 없습니다.", HttpStatus.FORBIDDEN)
        }
        return channel
    }

    @Transactional
    fun createWebhook(userId: Long, channelId: Long, request: CreateWebhookRequest): WebhookResponse {
        requireWebhookManager(channelId, userId)

        val webhook = webhookRepository.save(
            Webhook(
                channelId = channelId,
                name = request.name,
                isSecure = request.isSecure,
                token = if (request.isSecure) UUID.randomUUID().toString() else null,
                avatarUrl = request.avatarUrl,
                createdBy = userId,
            )
        )
        return WebhookResponse.of(webhook)
    }

    fun getWebhooks(userId: Long, channelId: Long): List<WebhookResponse> {
        val channel = channelService.findChannelOrThrow(channelId)
        teamPermissionService.requireTeamMember(channel.teamId, userId)
        return webhookRepository.findByChannelId(channelId).map { WebhookResponse.of(it) }
    }

    @Transactional
    fun updateWebhook(userId: Long, channelId: Long, webhookId: Long, request: UpdateWebhookRequest): WebhookResponse {
        requireWebhookManager(channelId, userId)
        val webhook = findWebhookOrThrow(webhookId)
        if (webhook.channelId != channelId) {
            throw ExpectedException("해당 채널의 웹훅이 아닙니다.", HttpStatus.BAD_REQUEST)
        }
        webhook.update(request.name, request.avatarUrl, request.isSecure)
        return WebhookResponse.of(webhook)
    }

    @Transactional
    fun deleteWebhook(userId: Long, channelId: Long, webhookId: Long) {
        requireWebhookManager(channelId, userId)
        val webhook = findWebhookOrThrow(webhookId)
        if (webhook.channelId != channelId) {
            throw ExpectedException("해당 채널의 웹훅이 아닙니다.", HttpStatus.BAD_REQUEST)
        }
        webhookRepository.delete(webhook)
    }
}
