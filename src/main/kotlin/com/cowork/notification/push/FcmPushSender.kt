package com.cowork.notification.push

import com.cowork.notification.domain.NotificationMessage
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingException
import com.google.firebase.messaging.MessagingErrorCode
import com.google.firebase.messaging.MulticastMessage
import com.google.firebase.messaging.Notification
import org.slf4j.LoggerFactory

class FcmPushSender(
    private val firebaseMessaging: FirebaseMessaging,
) : PushSender {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun send(tokens: List<String>, message: NotificationMessage): List<String> {
        if (tokens.isEmpty()) return emptyList()
        val invalidTokens = mutableListOf<String>()
        tokens.chunked(MAX_TOKENS_PER_REQUEST).forEach { chunk ->
            val multicast = MulticastMessage.builder()
                .addAllTokens(chunk)
                .setNotification(Notification.builder().setTitle(message.title).setBody(message.body).build())
                .putAllData(message.data + ("type" to message.type.name))
                .build()
            try {
                val response = firebaseMessaging.sendEachForMulticast(multicast)
                response.responses.forEachIndexed { index, result ->
                    val code = result.exception?.messagingErrorCode
                    if (code == MessagingErrorCode.UNREGISTERED || code == MessagingErrorCode.INVALID_ARGUMENT) {
                        invalidTokens += chunk[index]
                    }
                }
            } catch (e: FirebaseMessagingException) {
                log.warn("Fail to send FCM multicast message", e)
            }
        }
        return invalidTokens
    }

    companion object {
        private const val MAX_TOKENS_PER_REQUEST = 500
    }
}
