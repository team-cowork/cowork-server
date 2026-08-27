package com.cowork.channel.domain.channel.repository

import com.cowork.channel.domain.channel.entity.ChannelEventState
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ChannelEventStateRepository : JpaRepository<ChannelEventState, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM ChannelEventState s WHERE s.channelId = :channelId")
    fun findByChannelIdForUpdate(@Param("channelId") channelId: Long): ChannelEventState?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM ChannelEventState s WHERE s.channelId > :afterId ORDER BY s.channelId")
    fun findSnapshotBatch(@Param("afterId") afterId: Long, pageable: Pageable): List<ChannelEventState>
}
