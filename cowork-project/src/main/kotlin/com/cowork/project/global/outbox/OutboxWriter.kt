package com.cowork.project.global.outbox

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.sql.Types

@Component
class OutboxWriter(private val jdbcTemplate: JdbcTemplate, private val objectMapper: ObjectMapper) {
    private val namedJdbc = NamedParameterJdbcTemplate(jdbcTemplate)

    @Transactional(propagation = Propagation.MANDATORY)
    fun enqueue(topic: String, eventKey: String, payload: Any, partition: Int? = null, barrier: Boolean = false) {
        require(partition == null || partition >= 0) { "Kafka partition must not be negative" }

        val serializedPayload = objectMapper.writeValueAsString(payload)
        val fenceId = jdbcTemplate.queryForObject(LOCK_FENCE_SQL, Long::class.java)
        check(fenceId == FENCE_ID) { "Kafka outbox producer fence is missing." }

        val values =
            MapSqlParameterSource()
                .addValue("topic", topic)
                .addValue("partition", partition, Types.INTEGER)
                .addValue("eventKey", eventKey)
                .addValue("payload", serializedPayload)
                .addValue("barrier", barrier)
        namedJdbc.update(INSERT_SQL, values)
    }

    private companion object {
        const val FENCE_ID = 1L
        const val LOCK_FENCE_SQL = "SELECT id FROM tb_kafka_outbox_fence WHERE id = 1 FOR UPDATE"
        const val INSERT_SQL =
            "INSERT INTO tb_kafka_outbox " +
                "(topic, partition_id, event_key, payload, is_barrier) " +
                "VALUES (:topic, :partition, :eventKey, :payload, :barrier)"
    }
}
