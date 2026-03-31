package com.cowork.channel.service

import com.cowork.auth.service.TokenHasher
import com.cowork.channel.domain.ChannelViewType
import com.cowork.channel.domain.Webhook
import com.cowork.channel.dto.CreateWebhookRequest
import com.cowork.channel.dto.UpdateWebhookRequest
import com.cowork.channel.dto.WebhookResponse
import com.cowork.channel.event.ChannelDeletedEvent
import com.cowork.channel.repository.WebhookRepository
import com.cowork.global.error.BusinessException
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.event.EventListener
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest

/**
 * 채널 웹훅 관리와 웹훅 토큰 인증을 담당한다.
 *
 * - 토큰은 상수 시간 비교로 확인한다.
 * - 웹훅이 없을 때와 토큰이 틀릴 때 같은 오류를 돌려준다.
 */
@Service
class WebhookService(
    private val webhookRepository: WebhookRepository,
    private val channelAccessChecker: ChannelAccessChecker,
    @Value("\${cowork.public-base-url}") private val publicBaseUrl: String,
) {

    @Transactional
    fun create(channelId: Long, userId: Long, request: CreateWebhookRequest): WebhookResponse {
        val channel = channelAccessChecker.requireManageable(channelId, userId)
        channelAccessChecker.requireViewType(channel, ChannelViewType.TEXT, ChannelViewType.WEBHOOK)
        val webhook = webhookRepository.save(
            Webhook(
                channelId = channelId,
                name = request.name,
                avatarUrl = request.avatarUrl,
                token = TokenHasher.generate(),
                createdBy = userId,
            ),
        )
        return WebhookResponse.of(webhook, publicBaseUrl)
    }

    @Transactional(readOnly = true)
    fun getWebhooks(channelId: Long, userId: Long): List<WebhookResponse> {
        channelAccessChecker.requireManageable(channelId, userId)
        return webhookRepository.findAllByChannelIdOrderByIdAsc(channelId).map { WebhookResponse.of(it, publicBaseUrl) }
    }

    @Transactional
    fun update(channelId: Long, webhookId: Long, userId: Long, request: UpdateWebhookRequest): WebhookResponse {
        channelAccessChecker.requireManageable(channelId, userId)
        val webhook = findWebhook(channelId, webhookId)
        webhook.update(request.name, request.avatarUrl)
        if (request.rotateToken) {
            webhook.rotateToken(TokenHasher.generate())
        }
        return WebhookResponse.of(webhook, publicBaseUrl)
    }

    @Transactional
    fun delete(channelId: Long, webhookId: Long, userId: Long) {
        channelAccessChecker.requireManageable(channelId, userId)
        webhookRepository.delete(findWebhook(channelId, webhookId))
    }

    @Transactional(readOnly = true)
    fun authenticate(webhookId: Long, token: String): Webhook {
        val webhook = webhookRepository.findById(webhookId).orElse(null)
        if (webhook == null || !MessageDigest.isEqual(webhook.token.toByteArray(), token.toByteArray())) {
            throw BusinessException(HttpStatus.UNAUTHORIZED, "유효하지 않은 웹훅입니다.")
        }
        return webhook
    }

    @EventListener
    fun onChannelDeleted(event: ChannelDeletedEvent) {
        webhookRepository.deleteAllByChannelId(event.channelId)
    }

    private fun findWebhook(channelId: Long, webhookId: Long): Webhook =
        webhookRepository.findByIdAndChannelId(webhookId, channelId)
            ?: throw BusinessException(HttpStatus.NOT_FOUND, "웹훅을 찾을 수 없습니다.")
}
