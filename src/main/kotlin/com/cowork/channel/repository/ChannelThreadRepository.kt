package com.cowork.channel.repository

import com.cowork.channel.domain.ChannelThread
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query

interface ChannelThreadRepository : JpaRepository<ChannelThread, Long> {

    fun findAllByChannelIdAndIsArchivedOrderByIdDesc(channelId: Long, isArchived: Boolean): List<ChannelThread>

    fun findByIdAndChannelId(id: Long, channelId: Long): ChannelThread?

    fun existsByChannelIdAndParentMessageId(channelId: Long, parentMessageId: Long): Boolean

    @Modifying
    @Query("DELETE FROM ChannelThread t WHERE t.channelId = :channelId")
    fun deleteAllByChannelId(channelId: Long)
}
