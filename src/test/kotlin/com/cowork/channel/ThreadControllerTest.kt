package com.cowork.channel

import com.cowork.channel.controller.ThreadController
import com.cowork.channel.domain.ChannelThread
import com.cowork.channel.dto.ThreadResponse
import com.cowork.channel.service.ThreadService
import com.cowork.support.ControllerTestSupport
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.get

class ThreadControllerTest : ControllerTestSupport() {

    private val threadService = mockk<ThreadService>()
    private val mockMvc = mockMvc(ThreadController(threadService))

    @Test
    fun `보관 여부 기본값은 false로 조회한다`() {
        every { threadService.getThreads(10, 1, false) } returns listOf(
            ThreadResponse.from(ChannelThread(channelId = 10, name = "논의", parentMessageId = 5, createdBy = 1, id = 3)),
        )

        mockMvc.get("/channels/10/threads").andExpect {
            status { isOk() }
            jsonPath("$[0].parentMessageId") { value(5) }
        }

        verify { threadService.getThreads(10, 1, false) }
    }
}
