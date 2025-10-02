package com.cowork.team

import com.cowork.support.ControllerTestSupport
import com.cowork.team.controller.TeamMemberController
import com.cowork.team.service.TeamMemberService
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.patch

class TeamMemberControllerTest : ControllerTestSupport() {

    private val teamMemberService = mockk<TeamMemberService>()
    private val mockMvc = mockMvc(TeamMemberController(teamMemberService))

    @Test
    fun `알 수 없는 역할로 변경하면 400을 반환한다`() {
        mockMvc.patch("/teams/3/members/7/role") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"role":"SUPER"}"""
        }.andExpect { status { isBadRequest() } }
    }
}
