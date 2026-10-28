package com.cowork.channel.domain.meetingNote.service.support

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import team.themoment.sdk.exception.ExpectedException
import tools.jackson.core.JacksonException
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.ObjectReader

@Component
class MeetingNoteContentSupport(
    objectMapper: ObjectMapper,
) {

    // 중복 키는 파서마다 해석이 달라 클라이언트별로 다른 내용이 보일 수 있으므로 거부한다.
    private val reader: ObjectReader = objectMapper.reader().with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)

    // content는 LONGTEXT로 원문 그대로 저장되므로 DB 대신 서버에서 JSON 객체인지 검사한다.
    fun requireJsonObject(content: String) {
        val node = try {
            reader.readTree(content)
        } catch (e: JacksonException) {
            null
        }
        if (node == null || !node.isObject) {
            throw ExpectedException("회의록 내용은 JSON 객체 형식이어야 합니다.", HttpStatus.BAD_REQUEST)
        }
    }
}
