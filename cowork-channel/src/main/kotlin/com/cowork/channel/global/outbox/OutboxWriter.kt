package com.cowork.channel.global.outbox

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper

@Component
class OutboxWriter(private val jdbcTemplate: JdbcTemplate, private val objectMapper: ObjectMapper) {
    @Transactional(propagation = Propagation.MANDATORY)
    fun enqueue(topic: String, eventKey: String, payload: Any, partition: Int? = null) {
        require(partition == null || partition >= 0) { "Kafka partition must not be negative" }
        jdbcTemplate.update(
            "INSERT INTO tb_kafka_outbox (topic, partition_id, event_key, payload) VALUES (?, ?, ?, ?)",
            topic,
            partition,
            eventKey,
            serializePayload(payload),
        )
    }

    internal fun serializePayload(payload: Any): String = objectMapper.writeValueAsString(payload)
}
