package com.cowork.channel.domain.meetingNote.service

import com.cowork.channel.domain.meetingNote.presentation.data.request.SummarizeMeetingNoteRequest
import com.cowork.channel.domain.meetingNote.presentation.data.response.MeetingNoteSummaryResponse

interface SummarizeMeetingNoteService {
    fun execute(userId: Long, channelId: Long, request: SummarizeMeetingNoteRequest): MeetingNoteSummaryResponse
}
