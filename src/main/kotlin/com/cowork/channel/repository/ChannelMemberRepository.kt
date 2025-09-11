package com.cowork.channel.repository

import com.cowork.channel.domain.ChannelMember
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query

interface ChannelMemberRepository : JpaRepository<ChannelMember, Long> {

    fun findByChannelIdAndUserId(channelId: Long, userId: Long): ChannelMember?

    fun existsByChannelIdAndUserId(channelId: Long, userId: Long): Boolean

    fun findAllByChannelIdOrderByJoinedAtAsc(channelId: Long): List<ChannelMember>

    @Query("SELECT m.userId FROM ChannelMember m WHERE m.channelId = :channelId")
    fun findUserIdsByChannelId(channelId: Long): List<Long>

    @Query("SELECT m.channelId FROM ChannelMember m WHERE m.userId = :userId AND m.channelId IN :channelIds")
    fun findJoinedChannelIds(userId: Long, channelIds: Collection<Long>): List<Long>

    @Modifying
    @Query("DELETE FROM ChannelMember m WHERE m.channelId = :channelId")
    fun deleteAllByChannelId(channelId: Long)

    @Modifying
    @Query("DELETE FROM ChannelMember m WHERE m.userId = :userId AND m.channelId IN :channelIds")
    fun deleteAllByUserIdAndChannelIdIn(userId: Long, channelIds: Collection<Long>)
}
