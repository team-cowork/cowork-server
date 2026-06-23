package com.cowork.team.domain.teamInvite.presentation.data.request

import com.fasterxml.jackson.annotation.JsonProperty
import java.time.LocalDateTime

enum class InviteDuration(val value: String) {
    @JsonProperty("1d")
    ONE_DAY("1d"),

    @JsonProperty("7d")
    SEVEN_DAYS("7d"),

    @JsonProperty("30d")
    THIRTY_DAYS("30d"),

    @JsonProperty("never")
    NEVER("never"),
    ;

    fun toExpiresAt(from: LocalDateTime): LocalDateTime? = when (this) {
        ONE_DAY -> from.plusDays(1)
        SEVEN_DAYS -> from.plusDays(7)
        THIRTY_DAYS -> from.plusDays(30)
        NEVER -> null
    }
}

data class CreateInviteRequest(val duration: InviteDuration)
