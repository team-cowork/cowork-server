package com.cowork.channel.domain.meetingNote.service.impl

import com.cowork.channel.domain.meetingNote.repository.MeetingNoteRepository
import com.cowork.channel.domain.meetingNote.service.DeleteMeetingNoteService
import com.cowork.channel.domain.meetingNote.service.MeetingNoteAccessGuard
import com.cowork.channel.domain.meetingNote.service.support.MeetingNoteLookupSupport
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional
class DeleteMeetingNoteServiceImpl(
    private val meetingNoteRepository: MeetingNoteRepository,
    private val meetingNoteAccessGuard: MeetingNoteAccessGuard,
    private val meetingNoteLookupSupport: MeetingNoteLookupSupport,
) : DeleteMeetingNoteService {

    override fun deleteNote(userId: Long, channelId: Long, noteId: Long) {
        meetingNoteAccessGuard.requireChannelMember(channelId, userId)
        val note = meetingNoteLookupSupport.findNoteOrThrow(noteId, channelId)
        meetingNoteLookupSupport.requireNoteOwner(note, userId)
        meetingNoteRepository.delete(note)
    }
}
