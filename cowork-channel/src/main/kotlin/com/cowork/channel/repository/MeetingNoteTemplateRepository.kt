package com.cowork.channel.repository

import com.cowork.channel.domain.MeetingNoteTemplate
import org.springframework.data.jpa.repository.JpaRepository

interface MeetingNoteTemplateRepository : JpaRepository<MeetingNoteTemplate, Long> {

    fun findAllByChannelIdOrderByIdAsc(channelId: Long): List<MeetingNoteTemplate>

    fun findByChannelIdAndIsActiveTrue(channelId: Long): MeetingNoteTemplate?
}
