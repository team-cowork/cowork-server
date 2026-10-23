package com.cowork.channel.domain.channel.repository

import com.cowork.channel.domain.channel.entity.DmParticipant
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface DmParticipantRepository : JpaRepository<DmParticipant, Long> {

    fun findByChannelId(channelId: Long): List<DmParticipant>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT cm FROM DmParticipant cm WHERE cm.channelId = :channelId ORDER BY cm.id")
    fun findAllByChannelIdForUpdateOrderByIdAsc(@Param("channelId") channelId: Long): List<DmParticipant>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        "SELECT cm FROM DmParticipant cm " +
            "WHERE cm.channelId = :channelId AND cm.userId = :userId",
    )
    fun findByChannelIdAndUserIdForUpdate(
        @Param("channelId") channelId: Long,
        @Param("userId") userId: Long,
    ): DmParticipant?

    fun existsByChannelIdAndUserId(channelId: Long, userId: Long): Boolean
}
