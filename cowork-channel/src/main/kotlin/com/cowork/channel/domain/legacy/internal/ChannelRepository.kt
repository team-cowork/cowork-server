package com.cowork.channel.domain.legacy.internal

import org.springframework.data.jpa.repository.JpaRepository

interface ChannelRepository : JpaRepository<ChannelEntity, Long> {
    fun findAllByTeamIdOrderByPositionAscIdAsc(teamId: Long): List<ChannelEntity>
}
