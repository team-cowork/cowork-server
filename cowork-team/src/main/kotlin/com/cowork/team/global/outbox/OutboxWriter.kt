package com.cowork.team.global.outbox

import com.cowork.shared.outbox.JdbcKafkaOutboxWriter
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper

@Component
class OutboxWriter(jdbcTemplate: JdbcTemplate, objectMapper: ObjectMapper) {
    private val delegate = JdbcKafkaOutboxWriter(jdbcTemplate, objectMapper)

    @Transactional(propagation = Propagation.MANDATORY)
    fun enqueue(topic: String, eventKey: String, payload: Any, partition: Int? = null, barrier: Boolean = false) =
        delegate.enqueue(topic, eventKey, payload, partition, barrier)
}
