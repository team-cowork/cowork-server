package com.cowork.channel.domain.meetingNote.service.impl

import com.cowork.channel.domain.meetingNote.presentation.data.response.MeetingNoteResponse
import com.cowork.channel.domain.meetingNote.repository.MeetingNoteRepository
import com.cowork.channel.domain.meetingNote.service.ListMeetingNotesService
import com.cowork.channel.domain.meetingNote.service.MeetingNoteAccessGuard
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class ListMeetingNotesServiceImpl(
    private val meetingNoteRepository: MeetingNoteRepository,
    private val meetingNoteAccessGuard: MeetingNoteAccessGuard,
) : ListMeetingNotesService {

    override fun listNotes(userId: Long, channelId: Long): List<MeetingNoteResponse> {
        meetingNoteAccessGuard.requireChannelMember(channelId, userId)
        return meetingNoteRepository.findAllByChannelIdOrderByIdDesc(channelId)
            .map { MeetingNoteResponse.of(it) }
    }
}
