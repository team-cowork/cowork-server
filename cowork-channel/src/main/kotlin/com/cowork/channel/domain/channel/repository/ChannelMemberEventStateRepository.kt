package com.cowork.channel.domain.channel.repository

import com.cowork.channel.domain.channel.entity.ChannelMemberEventState
import com.cowork.channel.domain.channel.entity.ChannelMemberEventStateId
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ChannelMemberEventStateRepository : JpaRepository<ChannelMemberEventState, ChannelMemberEventStateId> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        "SELECT s FROM ChannelMemberEventState s " +
            "WHERE s.channelId = :channelId AND s.userId = :userId",
    )
    fun findByKeyForUpdate(
        @Param("channelId") channelId: Long,
        @Param("userId") userId: Long,
    ): ChannelMemberEventState?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        "SELECT s FROM ChannelMemberEventState s " +
            "WHERE s.channelId > :afterChannelId " +
            "OR (s.channelId = :afterChannelId AND s.userId > :afterUserId) " +
            "ORDER BY s.channelId, s.userId",
    )
    fun findSnapshotBatch(
        @Param("afterChannelId") afterChannelId: Long,
        @Param("afterUserId") afterUserId: Long,
        pageable: Pageable,
    ): List<ChannelMemberEventState>
}
