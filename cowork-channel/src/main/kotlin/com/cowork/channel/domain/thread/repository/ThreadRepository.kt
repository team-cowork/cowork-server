package com.cowork.channel.domain.thread.repository

import com.cowork.channel.domain.thread.entity.Thread
import com.cowork.channel.domain.thread.entity.ThreadStatus
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ThreadRepository : JpaRepository<Thread, Long> {

    fun findByChannelIdAndStatus(channelId: Long, status: ThreadStatus, pageable: Pageable): Page<Thread>

    fun findByChannelIdAndStatusAndIsArchivedFalse(
        channelId: Long,
        status: ThreadStatus,
        pageable: Pageable,
    ): Page<Thread>

    fun existsByParentMessageId(parentMessageId: String): Boolean

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM Thread t WHERE t.id = :threadId")
    fun findByIdForUpdate(@Param("threadId") threadId: Long): Thread?
}
