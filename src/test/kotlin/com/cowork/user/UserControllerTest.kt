package com.cowork.user

import com.cowork.global.storage.PresignedUploadResponse
import com.cowork.support.ControllerTestSupport
import com.cowork.user.controller.UserController
import com.cowork.user.service.UserService
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post

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

    @Test
    fun `프로필 이미지 업로드 URL을 발급한다`() {
        every { userService.createProfileImageUploadUrl(1, any()) } returns
            PresignedUploadResponse("https://upload", "profiles/1/a.png")

        mockMvc.post("/users/me/profile-image/presigned") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"contentType":"image/png","fileSize":1024}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.objectKey") { value("profiles/1/a.png") }
        }
    }

    @Test
    fun `파일 크기가 0 이하이면 400을 반환한다`() {
        mockMvc.post("/users/me/profile-image/presigned") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"contentType":"image/png","fileSize":0}"""
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `프로필 이미지를 삭제하면 204를 반환한다`() {
        every { userService.deleteProfileImage(1) } just runs

        mockMvc.delete("/users/me/profile-image").andExpect { status { isNoContent() } }
    }
}
