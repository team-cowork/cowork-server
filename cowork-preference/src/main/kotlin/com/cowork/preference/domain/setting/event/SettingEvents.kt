package com.cowork.preference.domain.setting.event

import com.cowork.preference.domain.setting.model.ResourceType
import com.cowork.preference.domain.setting.model.SettingSchema
import com.cowork.preference.global.messaging.event.PreferenceEvent
import com.cowork.preference.global.messaging.event.PreferenceEventPayload.basePayload
import com.cowork.preference.global.messaging.event.PreferenceTopics
import io.vertx.core.json.JsonObject
import java.time.Instant

object SettingEvents {
    const val SETTING_STATE_SCHEMA_VERSION = 1

    fun githubRepoSettingState(
        repoId: Long,
        settings: JsonObject?,
        occurredAt: Instant,
        snapshot: Boolean,
    ): PreferenceEvent {
        val deleted = settings == null
        val normalized = settings?.let {
            SettingSchema.resolve(ResourceType.GITHUB_REPO, it)
        }
        return PreferenceEvent(
            topic = PreferenceTopics.GITHUB_REPO_SETTING_STATE_TOPIC,
            key = repoId.toString(),
            payload = basePayload(if (deleted) "DELETE" else "UPSERT", occurredAt)
                .put("schemaVersion", 1)
                .put("repoId", repoId)
                .put("settings", normalized)
                .put("snapshot", snapshot),
            occurredAt = occurredAt,
        )
    }

    fun githubRepoSettingResult(operationId: String, result: JsonObject, occurredAt: Instant): PreferenceEvent =
        PreferenceEvent(
            topic = PreferenceTopics.GITHUB_REPO_SETTING_RESULT_TOPIC,
            key = operationId,
            payload = result,
            occurredAt = occurredAt,
        )

    fun statusChanged(
        accountId: Long,
        previousStatus: String?,
        newStatus: String?,
        reason: String,
        occurredAt: Instant = Instant.now(),
    ): PreferenceEvent = PreferenceEvent(
        topic = PreferenceTopics.STATUS_CHANGED_TOPIC,
        key = accountId.toString(),
        payload = JsonObject()
            .put("accountId", accountId)
            .put("previousStatus", previousStatus)
            .put("newStatus", newStatus)
            .put("reason", reason)
            .put("timestamp", occurredAt.toString()),
        occurredAt = occurredAt,
    )

    /** 알림 발송 판단에 쓰는 계정 설정만 싣는다. 검증을 통과하지 않는 과거 값은 소비자가 격리하지 않도록 null로 보낸다. */
    fun accountSettingState(
        accountId: Long,
        settings: JsonObject,
        occurredAt: Instant,
        snapshot: Boolean,
    ): PreferenceEvent = PreferenceEvent(
        topic = PreferenceTopics.ACCOUNT_SETTING_STATE_TOPIC,
        key = accountId.toString(),
        payload = basePayload("UPSERT", occurredAt)
            .put("schemaVersion", SETTING_STATE_SCHEMA_VERSION)
            .put("accountId", accountId)
            .put("notificationLevel", validSetting(ResourceType.ACCOUNT, settings, "notification_level"))
            .put("status", validSetting(ResourceType.ACCOUNT, settings, "status"))
            .put(
                "statusExpiresAt",
                (validSetting(ResourceType.ACCOUNT, settings, "status_expires_at") as? String)
                    ?.let { Instant.parse(it).toString() },
            )
            .put("timezone", validSetting(ResourceType.ACCOUNT, settings, "timezone"))
            .put(
                "dndSchedule",
                (validSetting(ResourceType.ACCOUNT, settings, "dnd_schedule") as? JsonObject)?.copy(),
            )
            .put("snapshot", snapshot),
        occurredAt = occurredAt,
    )

    /** 닉네임 형식 안내는 예시가 있을 때만 켠다. 형식 판정 기준이 아니므로 소비자는 안내 표시에만 쓴다. */
    fun teamSettingState(teamId: Long, settings: JsonObject, occurredAt: Instant, snapshot: Boolean): PreferenceEvent {
        val nicknameFormatExample = validSetting(ResourceType.TEAM, settings, "nickname_format_example")
        return PreferenceEvent(
            topic = PreferenceTopics.TEAM_SETTING_STATE_TOPIC,
            key = teamId.toString(),
            payload = basePayload("UPSERT", occurredAt)
                .put("schemaVersion", SETTING_STATE_SCHEMA_VERSION)
                .put("teamId", teamId)
                .put(
                    "defaultNotificationLevel",
                    validSetting(ResourceType.TEAM, settings, "default_notification_level"),
                )
                .put(
                    "nicknameFormatEnforced",
                    nicknameFormatExample != null && settings.getValue("nickname_format_enforced") == true,
                )
                .put("nicknameFormatExample", nicknameFormatExample)
                .put("tagSpamBlock", validSetting(ResourceType.TEAM, settings, "tag_spam_block") == true)
                .put("snapshot", snapshot),
            occurredAt = occurredAt,
        )
    }

    fun textChannelSettingState(
        channelId: Long,
        settings: JsonObject,
        occurredAt: Instant,
        snapshot: Boolean,
    ): PreferenceEvent = PreferenceEvent(
        topic = PreferenceTopics.TEXT_CHANNEL_SETTING_STATE_TOPIC,
        key = channelId.toString(),
        payload = basePayload("UPSERT", occurredAt)
            .put("schemaVersion", SETTING_STATE_SCHEMA_VERSION)
            .put("channelId", channelId)
            .put(
                "slowmodeSeconds",
                validSetting(ResourceType.TEXT_CHANNEL, settings, "slowmode_seconds") as? Int ?: 0,
            )
            .put("snapshot", snapshot),
        occurredAt = occurredAt,
    )

    /** maxParticipants 0은 제한 없음, bitrate null은 클라이언트 기본값을 뜻한다. */
    fun voiceChannelSettingState(
        channelId: Long,
        settings: JsonObject,
        occurredAt: Instant,
        snapshot: Boolean,
    ): PreferenceEvent = PreferenceEvent(
        topic = PreferenceTopics.VOICE_CHANNEL_SETTING_STATE_TOPIC,
        key = channelId.toString(),
        payload = basePayload("UPSERT", occurredAt)
            .put("schemaVersion", SETTING_STATE_SCHEMA_VERSION)
            .put("channelId", channelId)
            .put(
                "maxParticipants",
                validSetting(ResourceType.VOICE_CHANNEL, settings, "max_participants") as? Int ?: 0,
            )
            .put("bitrate", validSetting(ResourceType.VOICE_CHANNEL, settings, "bitrate"))
            .put("snapshot", snapshot),
        occurredAt = occurredAt,
    )

    private fun validSetting(type: ResourceType, settings: JsonObject, key: String): Any? {
        val value = settings.getValue(key) ?: return null
        return value.takeIf { SettingSchema.validate(type, JsonObject().put(key, value)) == null }
    }
}
