package com.cowork.admin

import com.cowork.admin.controller.AdminController
import com.cowork.admin.dto.AdminStatsResponse
import com.cowork.admin.service.AdminService
import com.cowork.support.ControllerTestSupport
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch

class AdminControllerTest : ControllerTestSupport() {

    private val adminService = mockk<AdminService>()
    private val mockMvc = mockMvc(AdminController(adminService))

    @Test
    fun `서비스 현황 통계를 반환한다`() {
        every { adminService.getStats(loginUser) } returns AdminStatsResponse(10, 2, 3, 4, 50, 1)

        mockMvc.get("/admin/stats").andExpect {
            status { isOk() }
            jsonPath("$.messages") { value(50) }
        }
    }

    @Test
    fun `알 수 없는 권한으로는 바꿀 수 없다`() {
        mockMvc.patch("/admin/users/2/role") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"role":"OWNER"}"""
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `사용자 권한을 바꾸면 204를 반환한다`() {
        every { adminService.changeUserRole(loginUser, 2, com.cowork.user.domain.UserRole.ADMIN) } just runs

        mockMvc.patch("/admin/users/2/role") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"role":"ADMIN"}"""
        }.andExpect { status { isNoContent() } }

        verify { adminService.changeUserRole(loginUser, 2, com.cowork.user.domain.UserRole.ADMIN) }
    }
}
