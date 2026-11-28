package com.cowork.team.global.outbox

import jakarta.persistence.EntityManager
import org.springframework.modulith.NamedInterface
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Component
@NamedInterface("outbox", propagate = false)
class NotificationPublisher(private val entityManager: EntityManager, private val outboxWriter: OutboxWriter) {
    @Transactional(propagation = Propagation.MANDATORY)
    fun publish(teamId: Long, event: NotificationTriggerEvent) {
        entityManager.flush()
        outboxWriter.enqueue(Topics.NOTIFICATION_TRIGGER, teamId.toString(), event)
    }
}
