package com.cowork.account

import com.cowork.channel.account.controller.SharedAccountController
import com.cowork.channel.account.service.SharedAccountService
import com.cowork.support.ControllerTestSupport
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.post

class SharedAccountControllerTest : ControllerTestSupport() {

    private val sharedAccountService = mockk<SharedAccountService>()
    private val mockMvc = mockMvc(SharedAccountController(sharedAccountService))

    @Test
    fun `제공자 없이 공유 계정을 등록할 수 없다`() {
        mockMvc.post("/channels/20/accounts") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"accountIdentifier":"admin"}"""
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `자격 증명은 2000자를 넘을 수 없다`() {
        mockMvc.post("/channels/20/accounts") {
            contentType = MediaType.APPLICATION_JSON
            content = json(mapOf("provider" to "CUSTOM", "credential" to "a".repeat(2001)))
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `공유 계정을 삭제하면 204를 반환한다`() {
        every { sharedAccountService.delete(20, 30, 1) } just runs

        mockMvc.delete("/channels/20/accounts/30").andExpect { status { isNoContent() } }
    }
}
