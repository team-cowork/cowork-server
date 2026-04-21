package com.cowork.preference.messaging

import io.vertx.core.json.JsonObject
import io.vertx.kafka.client.producer.KafkaProducer
import io.vertx.kafka.client.producer.KafkaProducerRecord
import io.vertx.kotlin.coroutines.coAwait
import java.time.Instant

class PreferenceProducer(private val producer: KafkaProducer<String, String>) {

    suspend fun publishStatusChanged(
        accountId: Long,
        previousStatus: String?,
        newStatus: String?,
        reason: String,
    ) {
        val payload = JsonObject()
            .put("accountId", accountId)
            .put("previousStatus", previousStatus)
            .put("newStatus", newStatus)
            .put("reason", reason)
            .put("timestamp", Instant.now().toString())

        val record = KafkaProducerRecord.create<String, String>(
            TOPIC_STATUS_CHANGED,
            accountId.toString(),
            payload.encode(),
        )
        producer.send(record).coAwait()
    }

    suspend fun publishTeamSettingsChanged(
        teamId: Long,
        changedSettings: JsonObject,
        settings: JsonObject,
    ) {
        val payload = JsonObject()
            .put("teamId", teamId)
            .put("changedSettings", changedSettings)
            .put("settings", settings)
            .put("timestamp", Instant.now().toString())

        val record = KafkaProducerRecord.create<String, String>(
            TOPIC_TEAM_SETTING_CHANGED,
            teamId.toString(),
            payload.encode(),
        )
        producer.send(record).coAwait()
    }

    companion object {
        const val TOPIC_STATUS_CHANGED = "preference.status.changed"
        const val TOPIC_TEAM_SETTING_CHANGED = "preference.team.setting.changed"
    }
}
