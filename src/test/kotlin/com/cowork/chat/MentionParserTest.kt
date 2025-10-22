package com.cowork.chat

import com.cowork.chat.service.MentionParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MentionParserTest {

    @Test
    fun `멘션 형식의 사용자 ID만 추출한다`() {
        val result = MentionParser.parse("<@1> 안녕하세요 <@22>, @33 <@abc> <@1>")

        assertEquals(setOf(1L, 22L), result)
    }

    @Test
    fun `멘션이 없으면 빈 집합을 반환한다`() {
        assertEquals(emptySet<Long>(), MentionParser.parse("그냥 메시지"))
    }
}
