package com.cowork.channel.service

import com.cowork.channel.domain.MeetingNote
import com.cowork.channel.dto.CreateMeetingNoteRequest
import com.cowork.channel.dto.MeetingNoteResponse
import com.cowork.channel.dto.UpdateMeetingNoteRequest
import com.cowork.channel.repository.ChannelMemberRepository
import com.cowork.channel.repository.MeetingNoteRepository
import com.cowork.channel.repository.MeetingNoteTemplateRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.themoment.sdk.exception.ExpectedException

@Service
@Transactional(readOnly = true)
class MeetingNoteService(
    private val meetingNoteRepository: MeetingNoteRepository,
    private val templateRepository: MeetingNoteTemplateRepository,
    private val channelMemberRepository: ChannelMemberRepository,
) {

    private fun requireChannelMember(channelId: Long, userId: Long) {
        if (!channelMemberRepository.existsByChannelIdAndUserId(channelId, userId)) {
            throw ExpectedException("채널 멤버만 접근할 수 있습니다.", HttpStatus.FORBIDDEN)
        }
    }

    fun listNotes(userId: Long, channelId: Long): List<MeetingNoteResponse> {
        requireChannelMember(channelId, userId)
        return meetingNoteRepository.findAllByChannelIdOrderByIdDesc(channelId)
            .map { MeetingNoteResponse.of(it) }
    }

    fun getNote(userId: Long, channelId: Long, noteId: Long): MeetingNoteResponse {
        requireChannelMember(channelId, userId)
        return MeetingNoteResponse.of(findNoteOrThrow(noteId, channelId))
    }

    @Transactional
    fun createNote(userId: Long, channelId: Long, request: CreateMeetingNoteRequest): MeetingNoteResponse {
        requireChannelMember(channelId, userId)
        val template = templateRepository.findById(request.templateId).orElseThrow {
            ExpectedException("템플릿을 찾을 수 없습니다. id=${request.templateId}", HttpStatus.NOT_FOUND)
        }
        if (template.channelId != channelId) {
            throw ExpectedException("템플릿을 찾을 수 없습니다.", HttpStatus.NOT_FOUND)
        }
        val note = meetingNoteRepository.save(
            MeetingNote(
                channelId = channelId,
                templateId = request.templateId,
                title = request.title,
                content = request.content,
                createdBy = userId,
            )
        )
        return MeetingNoteResponse.of(note)
    }

    @Transactional
    fun updateNote(userId: Long, channelId: Long, noteId: Long, request: UpdateMeetingNoteRequest): MeetingNoteResponse {
        requireChannelMember(channelId, userId)
        val note = findNoteOrThrow(noteId, channelId)
        if (note.createdBy != userId) {
            throw ExpectedException("작성자만 회의록을 수정할 수 있습니다.", HttpStatus.FORBIDDEN)
        }
        note.update(request.title, request.content)
        return MeetingNoteResponse.of(note)
    }

    @Transactional
    fun deleteNote(userId: Long, channelId: Long, noteId: Long) {
        requireChannelMember(channelId, userId)
        val note = findNoteOrThrow(noteId, channelId)
        if (note.createdBy != userId) {
            throw ExpectedException("작성자만 회의록을 삭제할 수 있습니다.", HttpStatus.FORBIDDEN)
        }
        meetingNoteRepository.delete(note)
    }

    private fun findNoteOrThrow(noteId: Long, channelId: Long): MeetingNote {
        val note = meetingNoteRepository.findById(noteId).orElseThrow {
            ExpectedException("회의록을 찾을 수 없습니다. id=$noteId", HttpStatus.NOT_FOUND)
        }
        if (note.channelId != channelId) {
            throw ExpectedException("회의록을 찾을 수 없습니다.", HttpStatus.NOT_FOUND)
        }
        return note
    }
}
