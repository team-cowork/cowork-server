package com.cowork.channel.domain.meetingNote.service

interface DeleteMeetingNoteService {
    fun deleteNote(userId: Long, channelId: Long, noteId: Long)
}
