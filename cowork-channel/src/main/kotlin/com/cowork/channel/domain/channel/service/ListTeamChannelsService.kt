package com.cowork.channel.domain.channel.service

import com.cowork.channel.domain.channel.entity.Channel

interface ListTeamChannelsService {
    fun execute(userId: Long, teamId: Long): List<Channel>
}
