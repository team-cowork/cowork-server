package com.cowork.channel.channel

import org.springframework.data.jpa.repository.JpaRepository

interface ChannelRepository : JpaRepository<ChannelEntity, Long> {
    fun findAllByTeamIdOrderByIdAsc(teamId: Long): List<ChannelEntity>
}

