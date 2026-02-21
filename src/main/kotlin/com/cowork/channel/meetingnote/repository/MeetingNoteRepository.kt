package com.cowork.channel.meetingnote.repository

import com.cowork.channel.meetingnote.domain.MeetingNote
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query

interface MeetingNoteRepository : JpaRepository<MeetingNote, Long> {

    fun findByIdAndChannelId(id: Long, channelId: Long): MeetingNote?

    @Query(
        """
        SELECT n FROM MeetingNote n
        WHERE n.channelId = :channelId AND (:before IS NULL OR n.id < :before)
        ORDER BY n.id DESC
        """,
    )
    fun findPage(channelId: Long, before: Long?, pageable: Pageable): List<MeetingNote>

    @Modifying
    @Query("DELETE FROM MeetingNote n WHERE n.channelId = :channelId")
    fun deleteAllByChannelId(channelId: Long)
}
