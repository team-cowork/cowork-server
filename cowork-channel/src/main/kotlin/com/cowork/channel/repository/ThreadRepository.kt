package com.cowork.channel.repository

import com.cowork.channel.domain.Thread
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface ThreadRepository : JpaRepository<Thread, Long> {

    fun findByChannelId(channelId: Long, pageable: Pageable): Page<Thread>

    fun findByChannelIdAndIsArchivedFalse(channelId: Long, pageable: Pageable): Page<Thread>
}
