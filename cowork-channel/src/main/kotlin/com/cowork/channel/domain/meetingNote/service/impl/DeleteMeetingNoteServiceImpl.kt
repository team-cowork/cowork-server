package com.cowork.channel.domain.meetingNote.service.impl

import com.cowork.channel.domain.meetingNote.event.MeetingNoteAttachmentPublisher
import com.cowork.channel.domain.meetingNote.repository.MeetingNoteRepository
import com.cowork.channel.domain.meetingNote.service.DeleteMeetingNoteService
import com.cowork.channel.domain.meetingNote.service.MeetingNoteAccessGuard
import com.cowork.channel.domain.meetingNote.service.support.MeetingNoteLookupSupport
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class DeleteMeetingNoteServiceImpl(
    private val meetingNoteRepository: MeetingNoteRepository,
    private val meetingNoteAccessGuard: MeetingNoteAccessGuard,
    private val meetingNoteLookupSupport: MeetingNoteLookupSupport,
    private val attachmentPublisher: MeetingNoteAttachmentPublisher,
) : DeleteMeetingNoteService {

    @Transactional
    override fun deleteNote(userId: Long, channelId: Long, noteId: Long) {
        meetingNoteAccessGuard.requireWritableChannelAccess(channelId, userId)
        val note = meetingNoteLookupSupport.findNoteOrThrow(noteId, channelId)
        meetingNoteLookupSupport.requireNoteOwner(note, userId)
        attachmentPublisher.publish(note, deleted = true)
        meetingNoteRepository.delete(note)
    }
}
