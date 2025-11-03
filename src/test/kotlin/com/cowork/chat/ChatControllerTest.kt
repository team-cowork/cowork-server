package com.cowork.chat

import com.cowork.chat.controller.ChatController
import com.cowork.chat.service.ChatFileService
import com.cowork.chat.service.ChatService
import com.cowork.support.ControllerTestSupport
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post

class ChatControllerTest : ControllerTestSupport() {

    private val chatService = mockk<ChatService>()
    private val chatFileService = mockk<ChatFileService>()
    private val mockMvc = mockMvc(ChatController(chatService, chatFileService))

    @Test
    fun `메시지는 4000자를 넘을 수 없다`() {
        mockMvc.post("/channels/10/messages") {
            contentType = MediaType.APPLICATION_JSON
            content = json(mapOf("content" to "a".repeat(4001)))
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `첨부파일은 한 번에 10개까지 보낼 수 있다`() {
        val attachments = (1..11).map { mapOf("objectKey" to "chat/10/1/$it/a.png", "fileName" to "a.png") }

        mockMvc.post("/channels/10/messages") {
            contentType = MediaType.APPLICATION_JSON
            content = json(mapOf("attachments" to attachments))
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `빈 내용으로는 메시지를 수정할 수 없다`() {
        mockMvc.patch("/channels/10/messages/5") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"content":" "}"""
        }.andExpect { status { isBadRequest() } }
    }
}
