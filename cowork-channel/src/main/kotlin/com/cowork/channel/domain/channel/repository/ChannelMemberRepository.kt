package com.cowork.channel.domain.channel.repository

import com.cowork.channel.domain.channel.entity.ChannelMember
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ChannelMemberRepository : JpaRepository<ChannelMember, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT cm FROM ChannelMember cm WHERE cm.channelId = :channelId ORDER BY cm.id")
    fun findSnapshotByChannelId(@Param("channelId") channelId: Long): List<ChannelMember>

    fun findByChannelId(channelId: Long): List<ChannelMember>

    fun existsByChannelIdAndUserId(channelId: Long, userId: Long): Boolean

    fun deleteAllByUserIdAndChannelIdIn(userId: Long, channelIds: List<Long>)

    fun findAllByUserId(userId: Long): List<ChannelMember>

    fun deleteAllByUserId(userId: Long)
}
