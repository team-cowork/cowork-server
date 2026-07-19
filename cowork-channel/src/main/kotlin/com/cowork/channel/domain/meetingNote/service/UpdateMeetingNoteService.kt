package com.cowork.channel.domain.meetingNote.service

import com.cowork.channel.domain.meetingNote.presentation.data.request.UpdateMeetingNoteRequest
import com.cowork.channel.domain.meetingNote.presentation.data.response.MeetingNoteResponse

interface UpdateMeetingNoteService {
    fun updateNote(userId: Long, channelId: Long, noteId: Long, request: UpdateMeetingNoteRequest): MeetingNoteResponse
}
