package com.cowork.user

import com.cowork.support.ControllerTestSupport
import com.cowork.user.controller.UserController
import com.cowork.user.service.UserService
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.patch

class UserControllerTest : ControllerTestSupport() {

    private val userService = mockk<UserService>()
    private val mockMvc = mockMvc(UserController(userService))

    @Test
    fun `닉네임은 50자를 넘을 수 없다`() {
        mockMvc.patch("/users/me") {
            contentType = MediaType.APPLICATION_JSON
            content = json(mapOf("nickname" to "a".repeat(51)))
        }.andExpect { status { isBadRequest() } }

        verify(exactly = 0) { userService.updateProfile(any(), any()) }
    }
}
