package com.cowork.channel.domain.meetingNote.service.impl

import com.cowork.channel.domain.meetingNote.presentation.data.request.UpdateMeetingNoteRequest
import com.cowork.channel.domain.meetingNote.presentation.data.response.MeetingNoteResponse
import com.cowork.channel.domain.meetingNote.service.MeetingNoteAccessGuard
import com.cowork.channel.domain.meetingNote.service.UpdateMeetingNoteService
import com.cowork.channel.domain.meetingNote.service.support.MeetingNoteLookupSupport
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.themoment.sdk.exception.ExpectedException

@Service
@Transactional
class UpdateMeetingNoteServiceImpl(
    private val meetingNoteAccessGuard: MeetingNoteAccessGuard,
    private val meetingNoteLookupSupport: MeetingNoteLookupSupport,
) : UpdateMeetingNoteService {

    override fun updateNote(
        userId: Long,
        channelId: Long,
        noteId: Long,
        request: UpdateMeetingNoteRequest,
    ): MeetingNoteResponse {
        meetingNoteAccessGuard.requireChannelMember(channelId, userId)
        request.title?.let {
            if (it.isBlank()) throw ExpectedException("회의록 제목은 공백일 수 없습니다.", HttpStatus.BAD_REQUEST)
            if (it.length > 200) throw ExpectedException("회의록 제목은 200자를 초과할 수 없습니다.", HttpStatus.BAD_REQUEST)
        }
        val note = meetingNoteLookupSupport.findNoteOrThrow(noteId, channelId)
        meetingNoteLookupSupport.requireNoteOwner(note, userId)
        val updatedTitle = request.title ?: note.title
        val updatedContent = request.content ?: note.content
        if (updatedTitle == note.title && updatedContent == note.content) {
            return MeetingNoteResponse.of(note)
        }
        note.update(request.title, request.content)
        return MeetingNoteResponse.of(note)
    }
}
