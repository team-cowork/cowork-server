package com.cowork.channel.domain.meetingNote.service

import com.cowork.channel.domain.meetingNote.presentation.data.response.MeetingNoteResponse

interface ListMeetingNotesService {
    fun listNotes(userId: Long, channelId: Long): List<MeetingNoteResponse>
}
