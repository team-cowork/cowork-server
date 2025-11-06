package com.cowork.preference.domain

import java.time.Instant
import java.time.format.DateTimeParseException

object SettingSchema {

    const val STATUS = "status"
    const val STATUS_EXPIRES_AT = "status_expires_at"
    const val MAX_PARTICIPANTS = "max_participants"

    const val STATUS_ONLINE = "ONLINE"
    const val STATUS_DO_NOT_DISTURB = "DO_NOT_DISTURB"

    private val ACCOUNT_KEYS = setOf(STATUS, STATUS_EXPIRES_AT, "marketing_email", "theme", "language", "time_format", "date_format")
    private val TEAM_KEYS = setOf("tag_spam_block", "nickname_format_enforced", "nickname_format_example")
    private val PROJECT_KEYS = setOf("default_channel_id")
    private val VOICE_CHANNEL_KEYS = setOf("bitrate", MAX_PARTICIPANTS)
    private val TEXT_CHANNEL_KEYS = setOf("slow_mode_seconds")

    private val STATUS_VALUES = setOf(STATUS_ONLINE, STATUS_DO_NOT_DISTURB)
    private val THEME_VALUES = setOf("WHITE", "BLACK")
    private val LANGUAGE_VALUES = setOf("KO", "EN")
    private val TIME_FORMAT_VALUES = setOf("12H", "24H")
    private val DATE_FORMAT_VALUES = setOf("YYYY_MM_DD", "MM_DD_YYYY", "DD_MM_YYYY")

    private const val MAX_VOICE_PARTICIPANTS = 50
    private const val MIN_BITRATE = 8_000
    private const val MAX_BITRATE = 384_000
    private const val MAX_SLOW_MODE_SECONDS = 21_600

    val PUBLIC_ACCOUNT_KEYS = setOf(STATUS)

    fun validKeys(type: ResourceType): Set<String> = when (type) {
        ResourceType.ACCOUNT -> ACCOUNT_KEYS
        ResourceType.TEAM -> TEAM_KEYS
        ResourceType.PROJECT -> PROJECT_KEYS
        ResourceType.VOICE_CHANNEL -> VOICE_CHANNEL_KEYS
        ResourceType.TEXT_CHANNEL -> TEXT_CHANNEL_KEYS
    }

    fun filter(type: ResourceType, raw: Map<String, Any?>): Map<String, Any?> {
        val keys = validKeys(type)
        return raw.filterKeys { it in keys }
    }

    /**
     * 유효하지 않으면 사용자에게 보여줄 오류 메시지를, 유효하면 null을 반환한다.
     * null 값은 해당 키 삭제를 의미하므로 검증하지 않는다.
     */
    fun validate(type: ResourceType, settings: Map<String, Any?>): String? = when (type) {
        ResourceType.ACCOUNT -> validateAccount(settings)
        ResourceType.TEAM -> validateTeam(settings)
        ResourceType.PROJECT -> validatePositiveLong(settings, "default_channel_id")
        ResourceType.VOICE_CHANNEL -> validateVoiceChannel(settings)
        ResourceType.TEXT_CHANNEL -> validateIntRange(settings, "slow_mode_seconds", 0, MAX_SLOW_MODE_SECONDS)
    }

    private fun validateAccount(settings: Map<String, Any?>): String? {
        validateEnum(settings, STATUS, STATUS_VALUES)?.let { return it }
        validateEnum(settings, "theme", THEME_VALUES)?.let { return it }
        validateEnum(settings, "language", LANGUAGE_VALUES)?.let { return it }
        validateEnum(settings, "time_format", TIME_FORMAT_VALUES)?.let { return it }
        validateEnum(settings, "date_format", DATE_FORMAT_VALUES)?.let { return it }
        validateBoolean(settings, "marketing_email")?.let { return it }
        settings[STATUS_EXPIRES_AT]?.let { value ->
            if (value !is String || parseInstant(value) == null) {
                return "status_expires_at은 ISO-8601 형식이어야 합니다."
            }
        }
        return null
    }

    private fun validateTeam(settings: Map<String, Any?>): String? {
        validateBoolean(settings, "tag_spam_block")?.let { return it }
        validateBoolean(settings, "nickname_format_enforced")?.let { return it }
        settings["nickname_format_example"]?.let { value ->
            if (value !is String || value.isBlank()) return "nickname_format_example은 비어 있지 않은 문자열이어야 합니다."
        }
        if (settings["nickname_format_enforced"] == true && (settings["nickname_format_example"] as? String).isNullOrBlank()) {
            return "닉네임 형식을 강제하려면 nickname_format_example이 필요합니다."
        }
        return null
    }

    private fun validateVoiceChannel(settings: Map<String, Any?>): String? {
        validateIntRange(settings, MAX_PARTICIPANTS, 1, MAX_VOICE_PARTICIPANTS)?.let { return it }
        return validateIntRange(settings, "bitrate", MIN_BITRATE, MAX_BITRATE)
    }

    fun parseInstant(value: String): Instant? = try {
        Instant.parse(value)
    } catch (e: DateTimeParseException) {
        null
    }

    private fun validateEnum(settings: Map<String, Any?>, key: String, allowed: Set<String>): String? {
        val value = settings[key] ?: return null
        return if (value is String && value in allowed) null else "${key}은 $allowed 중 하나여야 합니다."
    }

    private fun validateBoolean(settings: Map<String, Any?>, key: String): String? {
        val value = settings[key] ?: return null
        return if (value is Boolean) null else "${key}은 boolean이어야 합니다."
    }

    private fun validateIntRange(settings: Map<String, Any?>, key: String, min: Int, max: Int): String? {
        val value = settings[key] ?: return null
        val number = (value as? Number)?.takeIf { it.toDouble() % 1.0 == 0.0 }?.toLong()
        return if (number != null && number in min..max) null else "${key}은 $min 이상 $max 이하의 정수여야 합니다."
    }

    private fun validatePositiveLong(settings: Map<String, Any?>, key: String): String? {
        val value = settings[key] ?: return null
        val number = (value as? Number)?.takeIf { it.toDouble() % 1.0 == 0.0 }?.toLong()
        return if (number != null && number > 0) null else "${key}은 양의 정수여야 합니다."
    }
}
