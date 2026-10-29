package com.cowork.channel.domain.meetingNote.repository

import com.cowork.channel.domain.meetingNote.entity.MeetingNote
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface MeetingNoteRepository : JpaRepository<MeetingNote, Long> {

    fun findAllByChannelIdOrderByIdDesc(channelId: Long): List<MeetingNote>

    fun existsByTemplateId(templateId: Long): Boolean

    @Modifying
    @Query("DELETE FROM MeetingNote n WHERE n.channelId IN :channelIds")
    fun deleteAllByChannelIdIn(@Param("channelIds") channelIds: Collection<Long>)
}
