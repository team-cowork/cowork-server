package com.cowork.preference.domain

enum class ResourceType(
    val path: String,
) {
    ACCOUNT("account"),
    TEAM("team"),
    PROJECT("project"),
    TEXT_CHANNEL("text-channel"),
    VOICE_CHANNEL("voice-channel"),
    ;

    companion object {
        fun fromPath(path: String): ResourceType? = entries.firstOrNull { it.path == path }
    }
}
