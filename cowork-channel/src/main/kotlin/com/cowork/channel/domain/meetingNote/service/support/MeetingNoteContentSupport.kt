package com.cowork.channel.domain.meetingNote.service.support

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import team.themoment.sdk.exception.ExpectedException
import tools.jackson.core.JacksonException
import tools.jackson.databind.ObjectMapper

@Component
class MeetingNoteContentSupport(
    private val objectMapper: ObjectMapper,
) {

    // content는 LONGTEXT로 원문 그대로 저장되므로 DB 대신 서버에서 JSON 객체인지 검사한다.
    fun requireJsonObject(content: String) {
        val node = try {
            objectMapper.readTree(content)
        } catch (e: JacksonException) {
            null
        }
        if (node == null || !node.isObject) {
            throw ExpectedException("회의록 내용은 JSON 객체 형식이어야 합니다.", HttpStatus.BAD_REQUEST)
        }
    }
}
