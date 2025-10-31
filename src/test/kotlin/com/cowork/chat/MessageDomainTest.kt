package com.cowork.chat

import com.cowork.chat.domain.Message
import com.cowork.chat.domain.MessageType
import com.cowork.chat.service.MentionParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MessageDomainTest {

    private fun message() = Message(channelId = 10, senderId = 1, type = MessageType.TEXT, content = "hello")

    @Test
    fun `메시지를 수정하면 수정 표시를 남긴다`() {
        val message = message()

        message.edit("hello world")

        assertEquals("hello world", message.content)
        assertTrue(message.edited)
    }

    @Test
    fun `삭제한 메시지는 내용을 비운다`() {
        val message = message()

        message.softDelete()

        assertEquals("", message.content)
        assertTrue(message.deleted)
    }

    @Test
    fun `보낸 사람만 작성자로 판정한다`() {
        assertTrue(message().isSentBy(1))
        assertFalse(message().isSentBy(2))
    }

    @Test
    fun `같은 사용자를 여러 번 멘션해도 한 번만 반환한다`() {
        assertEquals(setOf(3L), MentionParser.parse("<@3> 확인 부탁해요 <@3>"))
    }

    @Test
    fun `Long 범위를 넘는 ID는 무시한다`() {
        assertEquals(emptySet<Long>(), MentionParser.parse("<@9999999999999999999>"))
    }
}
