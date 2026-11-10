package com.cowork.channel.domain.channel.presentation.controller

import com.cowork.channel.domain.channel.presentation.data.request.UpdateChannelRequest
import com.cowork.channel.domain.channel.presentation.data.response.ChannelResponse
import com.cowork.channel.domain.channel.service.UpdateChannelService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.validation.Validation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

class ChannelControllerTest {

    private val updateChannelService = mockk<UpdateChannelService>()
    private val objectMapper = mockk<ObjectMapper>()
    private val body = mockk<JsonNode>()

    private val controller = ChannelController(
        mockk(),
        mockk(),
        updateChannelService,
        mockk(),
        mockk(),
        objectMapper,
        Validation.buildDefaultValidatorFactory().validator,
        mockk(),
        mockk(),
    )

    @Test
    fun `채널 수정은 JsonNode 경로에서도 잘못된 이름을 서비스 호출 전에 거부한다`() {
        every { objectMapper.convertValue(body, UpdateChannelRequest::class.java) } returns
            UpdateChannelRequest(name = "   ")

        val ex = assertThrows(ExpectedException::class.java) {
            controller.updateChannel(1L, 1L, body)
        }

        assertEquals(HttpStatus.BAD_REQUEST, ex.statusCode)
        assertEquals("채널 이름은 공백일 수 없습니다.", ex.message)
        verify(exactly = 0) { updateChannelService.execute(any(), any(), any(), any()) }
    }

    @Test
    fun `채널 수정은 이름이 없으면 projectId 포함 여부를 유지해 서비스로 넘긴다`() {
        val request = UpdateChannelRequest(projectId = 5L)
        val response = mockk<ChannelResponse>()
        every { objectMapper.convertValue(body, UpdateChannelRequest::class.java) } returns request
        every { body.has("projectId") } returns true
        every { updateChannelService.execute(1L, 1L, request, true) } returns response

        assertSame(response, controller.updateChannel(1L, 1L, body))
    }
}
