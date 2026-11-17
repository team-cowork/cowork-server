package com.cowork.channel.domain.thread.repository

import com.cowork.channel.domain.thread.entity.ThreadState
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ThreadStateRepository : JpaRepository<ThreadState, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM ThreadState s WHERE s.threadId = :threadId")
    fun findForUpdate(@Param("threadId") threadId: Long): ThreadState?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM ThreadState s WHERE s.threadId > :afterId ORDER BY s.threadId")
    fun findSnapshotBatch(@Param("afterId") afterId: Long, pageable: Pageable): List<ThreadState>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM ThreadState s WHERE s.channelId IN :channelIds AND s.deleted = false ORDER BY s.threadId")
    fun findActiveByChannelIdsForUpdate(@Param("channelIds") channelIds: Collection<Long>): List<ThreadState>
}
