package com.cowork.channel.domain.meetingNote.service.impl

import com.cowork.channel.domain.meetingNote.client.MeetingNoteSummaryClient
import com.cowork.channel.domain.meetingNote.presentation.data.request.SummarizeMeetingNoteRequest
import com.cowork.channel.domain.meetingNote.presentation.data.response.MeetingNoteSummaryResponse
import com.cowork.channel.domain.meetingNote.service.MeetingNoteAccessGuard
import com.cowork.channel.domain.meetingNote.service.SummarizeMeetingNoteService
import org.springframework.stereotype.Service

@Service
class SummarizeMeetingNoteServiceImpl(
    private val accessGuard: MeetingNoteAccessGuard,
    private val client: MeetingNoteSummaryClient,
) : SummarizeMeetingNoteService {
    override fun execute(
        userId: Long,
        channelId: Long,
        request: SummarizeMeetingNoteRequest,
    ): MeetingNoteSummaryResponse {
        accessGuard.requireChannelAccess(channelId, userId)
        val content = request.content.trim()
        val excerpt = content.take(MAX_INPUT_LENGTH).trimEnd { it.isHighSurrogate() }
        val (summary, decisions, actionItems) = client.summarize(request.title, excerpt)
        return MeetingNoteSummaryResponse(
            summary,
            decisions,
            actionItems,
            excerpt.length < content.length,
        )
    }

    companion object {
        private const val MAX_INPUT_LENGTH = 12_000
    }
}
