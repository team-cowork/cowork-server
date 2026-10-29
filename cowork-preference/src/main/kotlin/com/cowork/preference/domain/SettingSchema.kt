package com.cowork.preference.domain

import io.vertx.core.json.JsonObject
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

object SettingSchema {

    private val ACCOUNT_KEYS = setOf(
        "status",
        "status_expires_at",
        "marketing_email",
        "theme",
        "language",
        "time_format",
        "date_format",
        "timezone",
        "notification_level",
        "dnd_schedule",
        "message_display",
        "enter_to_send",
    )
    private val TEAM_KEYS = setOf(
        "tag_spam_block",
        "nickname_format_enforced",
        "nickname_format_example",
        "default_notification_level",
    )
    private val PROJECT_KEYS = emptySet<String>() // TODO: 추후 확장 시 사용
    private val VOICE_CHANNEL_KEYS = setOf("bitrate", "max_participants")
    private val TEXT_CHANNEL_KEYS = setOf("slowmode_seconds")
    private val GITHUB_REPO_KEYS = setOf("label_auto_apply")

    // dnd_schedule도 PUT 전체 교체 계약 — start/end는 timezone 기준 HH:mm이며 end가 start보다 이르면 자정을 넘김
    private val DND_SCHEDULE_KEYS = setOf("enabled", "start", "end")

    private val ACCOUNT_STATUS_VALUES = setOf("ONLINE", "DO_NOT_DISTURB")
    private val ACCOUNT_THEME_VALUES = setOf("WHITE", "BLACK")
    private val ACCOUNT_LANGUAGE_VALUES = setOf("KO", "EN")
    private val ACCOUNT_TIME_FORMAT_VALUES = setOf("12H", "24H")
    private val ACCOUNT_DATE_FORMAT_VALUES =
        setOf("YYYY_MM_DD", "MM_DD_YYYY", "DD_MM_YYYY", "YYYY_DD_MM", "DD_YYYY_MM", "MM_YYYY_DD")
    private val NOTIFICATION_LEVEL_VALUES = NotificationLevel.entries.map { it.name }.toSet()
    private val MESSAGE_DISPLAY_VALUES = setOf("COZY", "COMPACT")
    private val HH_MM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private const val MAX_SLOWMODE_SECONDS = 21_600
    private const val MAX_VOICE_PARTICIPANTS = 99
    private val VOICE_BITRATE_RANGE = 8_000..128_000

    fun validKeys(type: ResourceType): Set<String> = when (type) {
        ResourceType.ACCOUNT -> ACCOUNT_KEYS
        ResourceType.TEAM -> TEAM_KEYS
        ResourceType.PROJECT -> PROJECT_KEYS
        ResourceType.VOICE_CHANNEL -> VOICE_CHANNEL_KEYS
        ResourceType.TEXT_CHANNEL -> TEXT_CHANNEL_KEYS
        ResourceType.GITHUB_REPO -> GITHUB_REPO_KEYS
    }

    fun filter(type: ResourceType, raw: JsonObject): JsonObject {
        val result = validKeys(type).fold(JsonObject()) { acc, key ->
            if (raw.containsKey(key)) acc.put(key, raw.getValue(key)) else acc
        }
        if (type == ResourceType.ACCOUNT) filterNested(result, "dnd_schedule", DND_SCHEDULE_KEYS)
        return result
    }

    private fun filterNested(settings: JsonObject, key: String, allowedKeys: Set<String>) {
        val raw = settings.getValue(key) as? JsonObject ?: return
        settings.put(
            key,
            allowedKeys.fold(JsonObject()) { acc, nestedKey ->
                if (raw.containsKey(nestedKey)) acc.put(nestedKey, raw.getValue(nestedKey)) else acc
            },
        )
    }

    fun validate(type: ResourceType, settings: JsonObject): String? = when (type) {
        ResourceType.ACCOUNT -> validateAccount(settings)
        ResourceType.TEAM -> validateTeam(settings)
        ResourceType.GITHUB_REPO -> validateGithubRepo(settings)
        ResourceType.TEXT_CHANNEL -> validateTextChannel(settings)
        ResourceType.VOICE_CHANNEL -> validateVoiceChannel(settings)
        ResourceType.PROJECT -> null
    }

    private fun validateAccount(settings: JsonObject): String? {
        settings.getString("status")?.let { status ->
            if (status !in ACCOUNT_STATUS_VALUES) return "status must be one of $ACCOUNT_STATUS_VALUES"
        }
        settings.getString("theme")?.let { theme ->
            if (theme !in ACCOUNT_THEME_VALUES) return "theme must be one of $ACCOUNT_THEME_VALUES"
        }
        settings.getString("language")?.let { lang ->
            if (lang !in ACCOUNT_LANGUAGE_VALUES) return "language must be one of $ACCOUNT_LANGUAGE_VALUES"
        }
        settings.getString("time_format")?.let { fmt ->
            if (fmt !in ACCOUNT_TIME_FORMAT_VALUES) return "time_format must be one of $ACCOUNT_TIME_FORMAT_VALUES"
        }
        settings.getString("date_format")?.let { fmt ->
            if (fmt !in ACCOUNT_DATE_FORMAT_VALUES) return "date_format must be one of $ACCOUNT_DATE_FORMAT_VALUES"
        }
        settings.getString("status_expires_at")?.let { expiresAt ->
            try {
                Instant.parse(expiresAt)
            } catch (_: DateTimeParseException) {
                return "status_expires_at must be a valid ISO-8601 datetime (e.g. 2026-04-19T12:00:00Z)"
            }
        }
        if (settings.containsKey("timezone")) {
            val timezone = settings.getValue("timezone")
            if (timezone !is String || !isZoneId(timezone)) {
                return "timezone must be a valid IANA time zone (e.g. Asia/Seoul)"
            }
        }
        enumError(settings, "notification_level", NOTIFICATION_LEVEL_VALUES)?.let { return it }
        enumError(settings, "message_display", MESSAGE_DISPLAY_VALUES)?.let { return it }
        if (settings.containsKey("enter_to_send") && settings.getValue("enter_to_send") !is Boolean) {
            return "enter_to_send must be a boolean"
        }
        if (settings.containsKey("dnd_schedule")) {
            validateDndSchedule(settings.getValue("dnd_schedule"))?.let { return it }
        }
        return null
    }

    private fun validateDndSchedule(value: Any?): String? {
        val schedule = value as? JsonObject ?: return "dnd_schedule must be an object"
        if (schedule.getValue("enabled") !is Boolean) return "dnd_schedule.enabled must be a boolean"
        for (key in listOf("start", "end")) {
            val time = schedule.getValue(key)
            if (time !is String || !isHourMinute(time)) return "dnd_schedule.$key must be HH:mm"
        }
        return null
    }

    private fun validateTextChannel(settings: JsonObject): String? {
        if (settings.containsKey("slowmode_seconds")) {
            val seconds = settings.getValue("slowmode_seconds")
            if (seconds !is Int || seconds !in 0..MAX_SLOWMODE_SECONDS) {
                return "slowmode_seconds must be an integer between 0 and $MAX_SLOWMODE_SECONDS"
            }
        }
        return null
    }

    // max_participants 0은 제한 없음이다. bitrate는 클라이언트가 오디오 publish에 쓰는 bps 값이다.
    private fun validateVoiceChannel(settings: JsonObject): String? {
        if (settings.containsKey("max_participants")) {
            val count = settings.getValue("max_participants")
            if (count !is Int || count !in 0..MAX_VOICE_PARTICIPANTS) {
                return "max_participants must be an integer between 0 and $MAX_VOICE_PARTICIPANTS"
            }
        }
        if (settings.containsKey("bitrate")) {
            val bitrate = settings.getValue("bitrate")
            if (bitrate !is Int || bitrate !in VOICE_BITRATE_RANGE) {
                return "bitrate must be an integer between ${VOICE_BITRATE_RANGE.first} and ${VOICE_BITRATE_RANGE.last}"
            }
        }
        return null
    }

    private fun enumError(settings: JsonObject, key: String, values: Set<String>): String? =
        if (settings.containsKey(key) && settings.getValue(key) !in values) "$key must be one of $values" else null

    private fun isZoneId(value: String): Boolean = try {
        ZoneId.of(value)
        true
    } catch (_: DateTimeException) {
        false
    }

    private fun isHourMinute(value: String): Boolean = try {
        LocalTime.parse(value, HH_MM)
        true
    } catch (_: DateTimeParseException) {
        false
    }

    private fun validateTeam(settings: JsonObject): String? {
        if (settings.containsKey("tag_spam_block") && settings.getValue("tag_spam_block") !is Boolean) {
            return "tag_spam_block must be a boolean"
        }
        if (settings.containsKey("nickname_format_enforced") &&
            settings.getValue("nickname_format_enforced") !is Boolean
        ) {
            return "nickname_format_enforced must be a boolean"
        }
        if (settings.containsKey("nickname_format_example")) {
            val example = settings.getValue("nickname_format_example")
            if (example !is String) return "nickname_format_example must be a string"
            if (example.isBlank()) return "nickname_format_example cannot be blank"
        }
        if (settings.getBoolean("nickname_format_enforced") == true &&
            settings.getString("nickname_format_example").isNullOrBlank()
        ) {
            return "nickname_format_example is required when nickname_format_enforced is true"
        }
        enumError(settings, "default_notification_level", NOTIFICATION_LEVEL_VALUES)?.let { return it }
        return null
    }

    private fun validateGithubRepo(settings: JsonObject): String? {
        if (settings.containsKey("label_auto_apply") && settings.getValue("label_auto_apply") !is Boolean) {
            return "label_auto_apply must be a boolean"
        }
        return null
    }
}
