package com.cowork.channel.domain.meetingNote.repository

import com.cowork.channel.domain.meetingNote.entity.MeetingNoteAttachmentState
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface MeetingNoteAttachmentStateRepository : JpaRepository<MeetingNoteAttachmentState, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM MeetingNoteAttachmentState s WHERE s.noteId = :noteId")
    fun findForUpdate(@Param("noteId") noteId: Long): MeetingNoteAttachmentState?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM MeetingNoteAttachmentState s WHERE s.noteId > :afterId ORDER BY s.noteId")
    fun findSnapshotBatch(@Param("afterId") afterId: Long, pageable: Pageable): List<MeetingNoteAttachmentState>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        "SELECT s FROM MeetingNoteAttachmentState s WHERE s.channelId IN :channelIds AND s.deleted = false ORDER BY s.noteId",
    )
    fun findActiveByChannelIdsForUpdate(
        @Param("channelIds") channelIds: Collection<Long>,
    ): List<MeetingNoteAttachmentState>
}
