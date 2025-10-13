package com.cowork.channel

import com.cowork.channel.controller.ChannelController
import com.cowork.channel.domain.Channel
import com.cowork.channel.domain.ChannelViewType
import com.cowork.channel.dto.ChannelResponse
import com.cowork.channel.service.ChannelMemberService
import com.cowork.channel.service.ChannelService
import com.cowork.support.ControllerTestSupport
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.post

class ChannelControllerTest : ControllerTestSupport() {

    private val channelService = mockk<ChannelService>()
    private val channelMemberService = mockk<ChannelMemberService>()
    private val mockMvc = mockMvc(ChannelController(channelService, channelMemberService))

    private val channel = Channel(
        teamId = 1, projectId = null, name = "general", viewType = ChannelViewType.TEXT, description = null,
        isPrivate = false, position = 0, createdBy = 1, id = 10,
    )

    @Test
    fun `채널 화면 유형이 없으면 400을 반환한다`() {
        mockMvc.post("/channels") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"teamId":1,"name":"general"}"""
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `채널을 만들면 201과 채널 종류를 반환한다`() {
        every { channelService.create(1, any()) } returns ChannelResponse.from(channel)

        mockMvc.post("/channels") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"teamId":1,"name":"general","viewType":"TEXT"}"""
        }.andExpect {
            status { isCreated() }
            jsonPath("$.type") { value("TEXT") }
        }
    }
}
