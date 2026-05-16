package com.cowork.channel.repository

import com.cowork.channel.domain.MeetingNote
import org.springframework.data.jpa.repository.JpaRepository

interface MeetingNoteRepository : JpaRepository<MeetingNote, Long> {

    fun findAllByChannelIdOrderByIdDesc(channelId: Long): List<MeetingNote>
}
