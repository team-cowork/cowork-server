package com.cowork.channel.domain.meetingNote.service.support

import com.cowork.channel.domain.meetingNote.entity.MeetingNote
import com.cowork.channel.domain.meetingNote.repository.MeetingNoteRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import team.themoment.sdk.exception.ExpectedException

@Component
class MeetingNoteLookupSupport(private val meetingNoteRepository: MeetingNoteRepository) {

    fun findNoteOrThrow(noteId: Long, channelId: Long): MeetingNote {
        val note = meetingNoteRepository.findById(noteId).orElseThrow {
            ExpectedException("회의록을 찾을 수 없습니다.", HttpStatus.NOT_FOUND)
        }
        if (note.channelId != channelId) {
            throw ExpectedException("회의록을 찾을 수 없습니다.", HttpStatus.NOT_FOUND)
        }
        return note
    }

    fun requireNoteOwner(note: MeetingNote, userId: Long) {
        if (note.createdBy != userId) {
            throw ExpectedException("회의록 작성자만 접근할 수 있습니다.", HttpStatus.FORBIDDEN)
        }
    }
}
