package com.cowork.channel.domain.channel.repository

import com.cowork.channel.domain.channel.entity.ChannelMember
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ChannelMemberRepository : JpaRepository<ChannelMember, Long> {

    fun findByChannelId(channelId: Long): List<ChannelMember>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT cm FROM ChannelMember cm WHERE cm.channelId = :channelId ORDER BY cm.id")
    fun findAllByChannelIdForUpdateOrderByIdAsc(@Param("channelId") channelId: Long): List<ChannelMember>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        "SELECT cm FROM ChannelMember cm " +
            "WHERE cm.channelId = :channelId AND cm.userId = :userId",
    )
    fun findByChannelIdAndUserIdForUpdate(
        @Param("channelId") channelId: Long,
        @Param("userId") userId: Long,
    ): ChannelMember?

    fun existsByChannelIdAndUserId(channelId: Long, userId: Long): Boolean

    fun deleteAllByUserIdAndChannelIdIn(userId: Long, channelIds: List<Long>)
}
