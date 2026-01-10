package com.cowork.notification.service

import com.cowork.notification.domain.NotificationMessage
import com.cowork.notification.push.PushSender
import com.cowork.notification.repository.DeviceTokenRepository
import com.cowork.preference.service.PreferenceReader
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * SSE와 FCM으로 알림을 보낸다.
 *
 * - 방해 금지 상태인 사용자는 제외한다.
 * - SSE로 전달한 사용자에게는 푸시를 보내지 않는다.
 * - FCM이 무효라고 응답한 토큰은 바로 지운다.
 */
@Service
class NotificationService(
    private val deviceTokenRepository: DeviceTokenRepository,
    private val sseEmitterRegistry: SseEmitterRegistry,
    private val pushSender: PushSender,
    private val preferenceReader: PreferenceReader,
) {

    @Transactional
    fun notify(recipientIds: Collection<Long>, message: NotificationMessage) {
        val recipients = recipientIds.toSet()
        if (recipients.isEmpty()) return

        recipients.forEach { sseEmitterRegistry.send(it, EVENT_NAME, message) }

        val pushTargets = recipients - preferenceReader.findDoNotDisturbUsers(recipients)
        if (pushTargets.isEmpty()) return
        val tokens = deviceTokenRepository.findAllByUserIdIn(pushTargets).map { it.token }
        val invalidTokens = pushSender.send(tokens, message)
        if (invalidTokens.isNotEmpty()) {
            deviceTokenRepository.deleteAllByTokenIn(invalidTokens)
        }
    }

    companion object {
        const val EVENT_NAME = "notification"
    }
}
