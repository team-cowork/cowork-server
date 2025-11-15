package com.cowork.preference.service

import com.cowork.preference.event.AccountStatusChangedEvent
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionalEventListener

@Component
class AccountStatusBroadcaster(
    private val messagingTemplate: SimpMessagingTemplate,
) {

    @TransactionalEventListener
    fun onStatusChanged(event: AccountStatusChangedEvent) {
        messagingTemplate.convertAndSend("/topic/users/${event.userId}/status", event)
    }
}
