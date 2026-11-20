package com.cowork.team.global.consumer

import com.cowork.team.global.policy.AccountPolicyProjection
import com.cowork.team.global.projection.ProjectionRecordProcessor
import com.cowork.team.global.projection.ProjectionStreams
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.time.Instant

private val accountActions = setOf(
    "create_team",
    "manage_account_roles",
    "manage_global_roadmaps",
    "read_all_team_roadmaps",
    "create_team_assignments",
    "update_team_assignment_status",
    "delete_team_assignments",
    "manage_chat_search_index",
    "manage_chat_message_quarantine",
    "view_chat_projections",
    "rebuild_chat_projections",
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AccountRolePolicyEvent(
    val eventType: String,
    val roleId: Long,
    val accountId: Long? = null,
    @field:JsonProperty("isDefault")
    val isDefault: Boolean? = null,
    val permissions: Map<String, Boolean>? = null,
    val deleted: Boolean,
    val occurredAt: Instant,
) {
    fun validKey(key: String?): Boolean = roleId > 0 && when (eventType) {
        "ROLE" ->
            key == "role:$roleId" && isDefault != null && permissions != null &&
                permissions.keys.all { it in accountActions }

        "ASSIGNMENT" -> accountId != null && accountId > 0 && key == "assignment:$accountId:$roleId"

        else -> false
    }
}

@Component
class AccountRolePolicyConsumer(
    private val objectMapper: ObjectMapper,
    private val accountPolicyProjection: AccountPolicyProjection,
    private val projectionRecordProcessor: ProjectionRecordProcessor,
    private val projectionStreams: ProjectionStreams,
) {
    @KafkaListener(
        topics = [AccountPolicyProjection.TOPIC],
        groupId = AccountPolicyProjection.GROUP,
        containerFactory = "accountRolePolicyListenerContainerFactory",
    )
    fun consume(record: ConsumerRecord<String, String>) {
        if (projectionRecordProcessor.processControlRecord(projectionStreams.accountRole, record)) return
        val event = runCatching {
            val json = objectMapper.readTree(record.value())
            require(json.path("deleted").isBoolean && json.path("roleId").isIntegralNumber)
            if (json.path("eventType").asString() == "ASSIGNMENT") require(json.path("accountId").isIntegralNumber)
            if (json.path("eventType").asString() == "ROLE") {
                require(json.path("isDefault").isBoolean && json.path("permissions").isObject)
                require(json.path("permissions").all { it.isBoolean })
            }
            objectMapper.treeToValue(json, AccountRolePolicyEvent::class.java)
        }.getOrNull()
        if (event == null || !event.validKey(record.key())) {
            projectionRecordProcessor.quarantineRecord(
                projectionStreams.accountRole,
                record,
                "Invalid account policy state",
            )
            return
        }
        projectionRecordProcessor.applyRecord(projectionStreams.accountRole, record) {
            accountPolicyProjection.apply(record.key(), event)
        }
    }
}
