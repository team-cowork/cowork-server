package com.cowork.chat.service

object MentionParser {

    private val MENTION_PATTERN = Regex("<@(\\d{1,19})>")

    fun parse(content: String): Set<Long> =
        MENTION_PATTERN.findAll(content)
            .mapNotNull { it.groupValues[1].toLongOrNull() }
            .toSet()
}
