package com.cowork.channel.domain.meetingNote.service

import com.cowork.channel.domain.meetingNote.presentation.data.request.CreateMeetingNoteRequest
import com.cowork.channel.domain.meetingNote.presentation.data.response.MeetingNoteResponse

interface CreateMeetingNoteService {
    fun createNote(userId: Long, channelId: Long, request: CreateMeetingNoteRequest): MeetingNoteResponse
}
