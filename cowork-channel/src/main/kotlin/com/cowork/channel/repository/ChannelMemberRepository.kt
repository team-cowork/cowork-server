package com.cowork.channel.repository

import com.cowork.channel.domain.ChannelMember
import org.springframework.data.jpa.repository.JpaRepository

interface ChannelMemberRepository : JpaRepository<ChannelMember, Long> {

    fun findByChannelId(channelId: Long): List<ChannelMember>

    fun existsByChannelIdAndUserId(channelId: Long, userId: Long): Boolean

    fun deleteAllByUserIdAndChannelIdIn(userId: Long, channelIds: List<Long>)

    fun deleteAllByUserId(userId: Long)
}
