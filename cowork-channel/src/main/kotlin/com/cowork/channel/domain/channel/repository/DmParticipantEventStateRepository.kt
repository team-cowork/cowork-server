package com.cowork.channel.domain.channel.repository

import com.cowork.channel.domain.channel.entity.DmParticipantEventState
import com.cowork.channel.domain.channel.entity.DmParticipantEventStateId
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface DmParticipantEventStateRepository : JpaRepository<DmParticipantEventState, DmParticipantEventStateId> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        "SELECT s FROM DmParticipantEventState s " +
            "WHERE s.channelId = :channelId AND s.userId = :userId",
    )
    fun findByKeyForUpdate(
        @Param("channelId") channelId: Long,
        @Param("userId") userId: Long,
    ): DmParticipantEventState?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        "SELECT s FROM DmParticipantEventState s " +
            "WHERE s.channelType = 'DM' AND (s.channelId > :afterChannelId " +
            "OR (s.channelId = :afterChannelId AND s.userId > :afterUserId)) " +
            "ORDER BY s.channelId, s.userId",
    )
    fun findSnapshotBatch(
        @Param("afterChannelId") afterChannelId: Long,
        @Param("afterUserId") afterUserId: Long,
        pageable: Pageable,
    ): List<DmParticipantEventState>
}
