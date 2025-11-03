package com.cowork.chat

import com.cowork.chat.controller.ChatController
import com.cowork.chat.dto.MessagePageResponse
import com.cowork.chat.service.ChatFileService
import com.cowork.chat.service.ChatService
import com.cowork.support.ControllerTestSupport
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
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

    @Test
    fun `메시지 목록 기본 크기는 50개다`() {
        every { chatService.getMessages(10, 1, null, 50, null) } returns MessagePageResponse(emptyList(), null)

        mockMvc.get("/channels/10/messages").andExpect { status { isOk() } }

        verify { chatService.getMessages(10, 1, null, 50, null) }
    }

    @Test
    fun `스레드 ID를 넘기면 스레드 메시지를 조회한다`() {
        every { chatService.getMessages(10, 1, 99, 20, 3) } returns MessagePageResponse(emptyList(), null)

        mockMvc.get("/channels/10/messages") {
            param("before", "99")
            param("limit", "20")
            param("threadId", "3")
        }.andExpect { status { isOk() } }
    }

    @Test
    fun `메시지를 삭제하면 204를 반환한다`() {
        every { chatService.delete(10, 5, 1) } just runs

        mockMvc.delete("/channels/10/messages/5").andExpect { status { isNoContent() } }
    }

    @Test
    fun `업로드 확정 시 다운로드 URL을 반환한다`() {
        every { chatFileService.confirmUpload(10, 1, "chat/10/1/x/a.pdf") } returns "https://download/a.pdf"

        mockMvc.post("/channels/10/files/confirm") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"objectKey":"chat/10/1/x/a.pdf"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.fileUrl") { value("https://download/a.pdf") }
        }
    }
}
