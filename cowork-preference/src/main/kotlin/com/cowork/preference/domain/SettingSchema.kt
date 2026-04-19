package com.cowork.preference.domain

import io.vertx.core.json.JsonObject

object SettingSchema {

    private val ACCOUNT_KEYS = setOf("status", "status_expires_at", "marketing_email", "theme", "language", "time_format")
    private val TEAM_KEYS = setOf("tag_spam_block")
    private val PROJECT_KEYS = emptySet<String>()
    private val VOICE_CHANNEL_KEYS = setOf("bitrate", "max_participants")
    private val TEXT_CHANNEL_KEYS = setOf("webhook")
    val NOTIFICATION_KEYS = setOf("notification")

    private val ACCOUNT_STATUS_VALUES = setOf("ONLINE", "DO_NOT_DISTURB")
    private val ACCOUNT_THEME_VALUES = setOf("WHITE", "BLACK")
    private val ACCOUNT_LANGUAGE_VALUES = setOf("KO", "EN")
    private val ACCOUNT_TIME_FORMAT_VALUES = setOf("12H", "24H")

    fun validKeys(type: ResourceType): Set<String> = when (type) {
        ResourceType.ACCOUNT -> ACCOUNT_KEYS
        ResourceType.TEAM -> TEAM_KEYS
        ResourceType.PROJECT -> PROJECT_KEYS
        ResourceType.VOICE_CHANNEL -> VOICE_CHANNEL_KEYS
        ResourceType.TEXT_CHANNEL -> TEXT_CHANNEL_KEYS
    }

    fun filter(type: ResourceType, raw: JsonObject): JsonObject =
        validKeys(type).fold(JsonObject()) { acc, key ->
            if (raw.containsKey(key)) acc.put(key, raw.getValue(key)) else acc
        }

    fun validate(type: ResourceType, settings: JsonObject): String? {
        if (type != ResourceType.ACCOUNT) return null

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
        return null
    }
}
