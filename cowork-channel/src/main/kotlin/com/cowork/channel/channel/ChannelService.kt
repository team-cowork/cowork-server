package com.cowork.channel.channel

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ChannelService(
    private val channelRepository: ChannelRepository,
) {

    @Transactional(readOnly = true)
    fun listByTeam(teamId: Long): List<ChannelEntity> =
        channelRepository.findAllByTeamIdOrderByIdAsc(teamId)
}

