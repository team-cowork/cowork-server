package com.cowork.channel.domain.meetingNote.service.impl

import com.cowork.channel.domain.meetingNote.presentation.data.response.MeetingNoteResponse
import com.cowork.channel.domain.meetingNote.service.GetMeetingNoteService
import com.cowork.channel.domain.meetingNote.service.MeetingNoteAccessGuard
import com.cowork.channel.domain.meetingNote.service.support.MeetingNoteLookupSupport
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class GetMeetingNoteServiceImpl(
    private val meetingNoteAccessGuard: MeetingNoteAccessGuard,
    private val meetingNoteLookupSupport: MeetingNoteLookupSupport,
) : GetMeetingNoteService {

    override fun getNote(userId: Long, channelId: Long, noteId: Long): MeetingNoteResponse {
        meetingNoteAccessGuard.requireChannelMember(channelId, userId)
        return MeetingNoteResponse.of(meetingNoteLookupSupport.findNoteOrThrow(noteId, channelId))
    }
}
