package com.cowork.chat.repository

import com.cowork.chat.domain.Message
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query

interface MessageRepository : JpaRepository<Message, Long> {

    fun findByIdAndChannelId(id: Long, channelId: Long): Message?

    @Query(
        """
        SELECT m FROM Message m
        WHERE m.channelId = :channelId
          AND m.threadId IS NULL
          AND (:before IS NULL OR m.id < :before)
        ORDER BY m.id DESC
        """,
    )
    fun findChannelPage(channelId: Long, before: Long?, pageable: Pageable): List<Message>

    @Query(
        """
        SELECT m FROM Message m
        WHERE m.channelId = :channelId
          AND m.threadId = :threadId
          AND (:before IS NULL OR m.id < :before)
        ORDER BY m.id DESC
        """,
    )
    fun findThreadPage(channelId: Long, threadId: Long, before: Long?, pageable: Pageable): List<Message>

    @Query(
        """
        SELECT m FROM Message m
        WHERE m.channelId IN :channelIds
          AND m.deleted = false
          AND m.content LIKE CONCAT('%', :keyword, '%')
          AND (:before IS NULL OR m.id < :before)
        ORDER BY m.id DESC
        """,
    )
    fun search(channelIds: Collection<Long>, keyword: String, before: Long?, pageable: Pageable): List<Message>

    @Modifying
    @Query("DELETE FROM Message m WHERE m.channelId = :channelId")
    fun deleteAllByChannelId(channelId: Long)
}
