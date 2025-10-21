package com.cowork.chat.repository

import com.cowork.chat.domain.MessageAttachment
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query

interface MessageAttachmentRepository : JpaRepository<MessageAttachment, Long> {

    fun findAllByMessageIdIn(messageIds: Collection<Long>): List<MessageAttachment>

    fun findAllByMessageId(messageId: Long): List<MessageAttachment>

    fun existsByObjectKey(objectKey: String): Boolean

    @Query(
        """
        SELECT a FROM MessageAttachment a
        WHERE a.channelId = :channelId
          AND (:before IS NULL OR a.id < :before)
        ORDER BY a.id DESC
        """,
    )
    fun findChannelFiles(channelId: Long, before: Long?, pageable: Pageable): List<MessageAttachment>

    @Query("SELECT a.objectKey FROM MessageAttachment a WHERE a.channelId = :channelId")
    fun findObjectKeysByChannelId(channelId: Long): List<String>

    @Modifying
    @Query("DELETE FROM MessageAttachment a WHERE a.channelId = :channelId")
    fun deleteAllByChannelId(channelId: Long)
}
