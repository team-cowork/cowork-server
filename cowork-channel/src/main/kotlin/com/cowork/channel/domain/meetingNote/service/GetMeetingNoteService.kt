package com.cowork.channel.domain.meetingNote.service

import com.cowork.channel.domain.meetingNote.presentation.data.response.MeetingNoteResponse

interface GetMeetingNoteService {
    fun getNote(userId: Long, channelId: Long, noteId: Long): MeetingNoteResponse
}
