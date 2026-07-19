package com.cowork.channel.domain.meetingNote.repository

import com.cowork.channel.domain.meetingNote.entity.MeetingNote
import org.springframework.data.jpa.repository.JpaRepository

interface MeetingNoteRepository : JpaRepository<MeetingNote, Long> {

    fun findAllByChannelIdOrderByIdDesc(channelId: Long): List<MeetingNote>
}
