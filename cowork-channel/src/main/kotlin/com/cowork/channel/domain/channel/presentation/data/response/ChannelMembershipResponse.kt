package com.cowork.channel.domain.channel.presentation.data.response

import com.fasterxml.jackson.annotation.JsonProperty

/** cowork-voice 등 서비스 간 멤버십 검증 호출(`/internal/channels/{channelId}/members/{userId}`)의 응답. */
data class ChannelMembershipResponse(@JsonProperty("team_id") val teamId: Long?)
