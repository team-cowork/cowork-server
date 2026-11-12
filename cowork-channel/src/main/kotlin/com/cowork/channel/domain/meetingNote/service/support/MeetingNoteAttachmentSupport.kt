package com.cowork.channel.domain.meetingNote.service.support

import com.cowork.channel.domain.meetingNote.entity.MeetingNoteAttachment
import com.cowork.channel.domain.meetingNote.presentation.data.request.MeetingNoteAttachmentRequest
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import team.themoment.sdk.exception.ExpectedException

@Component
class MeetingNoteAttachmentSupport {
    fun validate(
        attachments: List<MeetingNoteAttachmentRequest>,
        channelId: Long,
        userId: Long,
    ): List<MeetingNoteAttachment> {
        if (attachments.size > 20 || attachments.map { it.objectKey }.distinct().size != attachments.size) {
            throw ExpectedException("첨부파일은 중복 없이 최대 20개까지 등록할 수 있습니다.", HttpStatus.BAD_REQUEST)
        }
        val ownedKey =
            Regex(
                "chat-files/$channelId/$userId/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.[a-z0-9]+",
            )
        return attachments.map {
            if (it.objectKey.length > 200 || !ownedKey.matches(it.objectKey)) {
                throw ExpectedException("직접 업로드한 해당 채널의 첨부파일만 등록할 수 있습니다.", HttpStatus.BAD_REQUEST)
            }
            if (it.name.isBlank() || it.name.length > 255 || it.name.any { char -> char.isISOControl() }) {
                throw ExpectedException("첨부파일 이름이 올바르지 않습니다.", HttpStatus.BAD_REQUEST)
            }
            MeetingNoteAttachment(it.objectKey, it.name)
        }
    }
}
