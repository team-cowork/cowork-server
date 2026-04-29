package com.cowork.channel.repository

import com.cowork.channel.domain.Channel
import org.springframework.data.jpa.repository.JpaRepository

interface ChannelRepository : JpaRepository<Channel, Long> {

    fun findAllByTeamIdOrderByIdAsc(teamId: Long): List<Channel>
}
