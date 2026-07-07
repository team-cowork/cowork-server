package com.cowork.channel.domain.channel.service

import com.cowork.channel.domain.channel.entity.Channel
import com.cowork.channel.domain.channel.entity.ChannelType
import com.cowork.channel.domain.channel.repository.ChannelRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import team.themoment.sdk.exception.ExpectedException

@Service
class ChannelAccessGuard(private val channelRepository: ChannelRepository) {

    fun findChannelOrThrow(channelId: Long): Channel = channelRepository.findById(channelId).orElseThrow {
        ExpectedException("채널을 찾을 수 없습니다.", HttpStatus.NOT_FOUND)
    }

    /** DM 채널이면 거부하고, 팀 채널이면 non-null teamId를 반환한다. */
    fun requireTeamChannel(channel: Channel): Long {
        val teamId = channel.teamId
        if (channel.type == ChannelType.DM || teamId == null) {
            throw ExpectedException("DM 채널에서는 지원하지 않는 기능입니다.", HttpStatus.BAD_REQUEST)
        }
        return teamId
    }
}
