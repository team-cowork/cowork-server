package com.cowork.channel.domain.meetingNote.repository

import com.cowork.channel.domain.meetingNote.entity.MeetingNoteTemplate
import org.springframework.data.jpa.repository.JpaRepository

interface MeetingNoteTemplateRepository : JpaRepository<MeetingNoteTemplate, Long> {

    fun findAllByChannelIdOrderByIdAsc(channelId: Long): List<MeetingNoteTemplate>

    fun findByChannelIdAndIsActiveTrue(channelId: Long): MeetingNoteTemplate?
}
