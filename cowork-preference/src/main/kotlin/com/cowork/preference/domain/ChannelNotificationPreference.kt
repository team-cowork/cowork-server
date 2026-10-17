package com.cowork.preference.domain

import io.vertx.core.json.JsonObject
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

enum class NotificationLevel {
    ALL,
    MENTIONS,
    NONE,
}

data class ChannelNotificationPreference(
    val accountId: Long,
    val channelId: Long,
    val notificationLevel: NotificationLevel,
    val mutedUntil: Instant?,
    val stateOccurredAt: OffsetDateTime,
) {
    val notification: Boolean = notificationLevel != NotificationLevel.NONE
}

/**
 * 계정의 채널별 알림 설정. `notification`은 `notification_level`에서 파생한 하위 호환 값이다.
 */
object ChannelNotificationSettings {

    const val NOTIFICATION = "notification"
    const val NOTIFICATION_LEVEL = "notification_level"
    const val MUTED_UNTIL = "muted_until"

    /** PUT 전체 교체 요청을 저장 형태로 바꾼다. `notification_level`이 `notification`보다 우선한다. */
    fun fromRequest(raw: JsonObject): JsonObject {
        val level = when {
            raw.containsKey(NOTIFICATION_LEVEL) -> parseLevel(raw.getValue(NOTIFICATION_LEVEL))
            raw.containsKey(NOTIFICATION) -> {
                val enabled = raw.getValue(NOTIFICATION)
                require(enabled is Boolean) { "$NOTIFICATION must be a boolean" }
                if (enabled) NotificationLevel.ALL else NotificationLevel.NONE
            }
            else -> NotificationLevel.ALL
        }
        val mutedUntil = raw.getValue(MUTED_UNTIL)?.let { value ->
            require(value is String) { "$MUTED_UNTIL must be an ISO-8601 datetime string" }
            try {
                Instant.parse(value)
            } catch (_: DateTimeParseException) {
                throw IllegalArgumentException(
                    "$MUTED_UNTIL must be a valid ISO-8601 datetime (e.g. 2026-04-19T12:00:00Z)",
                )
            }
        }
        return toJson(level, mutedUntil)
    }

    fun level(settings: JsonObject?): NotificationLevel {
        val stored = settings?.getValue(NOTIFICATION_LEVEL) as? String
        NotificationLevel.entries.firstOrNull { it.name == stored }?.let { return it }
        return if (settings?.getValue(NOTIFICATION) == false) NotificationLevel.NONE else NotificationLevel.ALL
    }

    fun mutedUntil(settings: JsonObject?): Instant? =
        (settings?.getValue(MUTED_UNTIL) as? String)?.let { runCatching { Instant.parse(it) }.getOrNull() }

    fun normalize(settings: JsonObject?): JsonObject = toJson(level(settings), mutedUntil(settings))

    private fun toJson(level: NotificationLevel, mutedUntil: Instant?): JsonObject = JsonObject()
        .put(NOTIFICATION, level != NotificationLevel.NONE)
        .put(NOTIFICATION_LEVEL, level.name)
        .put(MUTED_UNTIL, mutedUntil?.toString())

    private fun parseLevel(value: Any?): NotificationLevel =
        NotificationLevel.entries.firstOrNull { it.name == value }
            ?: throw IllegalArgumentException(
                "$NOTIFICATION_LEVEL must be one of ${NotificationLevel.entries.map { it.name }}",
            )
}
