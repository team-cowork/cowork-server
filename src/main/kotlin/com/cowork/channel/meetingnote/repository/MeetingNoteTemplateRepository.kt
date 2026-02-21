package com.cowork.channel.meetingnote.repository

import com.cowork.channel.meetingnote.domain.MeetingNoteTemplate
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface MeetingNoteTemplateRepository : JpaRepository<MeetingNoteTemplate, Long> {

    fun findAllByChannelIdOrderByIdAsc(channelId: Long): List<MeetingNoteTemplate>

    fun findByIdAndChannelId(id: Long, channelId: Long): MeetingNoteTemplate?

    fun findFirstByChannelIdAndIsActiveTrue(channelId: Long): MeetingNoteTemplate?

    @Query("SELECT t.id FROM MeetingNoteTemplate t WHERE t.channelId = :channelId")
    fun findIdsByChannelId(channelId: Long): List<Long>
}
