package com.cowork.notification.push

import com.cowork.notification.domain.NotificationMessage
import org.slf4j.LoggerFactory

class NoOpPushSender : PushSender {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun send(tokens: List<String>, message: NotificationMessage): List<String> {
        log.debug("Skip push for {} tokens because FCM is not configured", tokens.size)
        return emptyList()
    }
}
