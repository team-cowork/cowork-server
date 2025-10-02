package com.cowork.team

import com.cowork.support.ControllerTestSupport
import com.cowork.team.controller.TeamMemberController
import com.cowork.team.service.TeamMemberService
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

    @Test
    fun `팀 멤버 여부를 반환한다`() {
        every { teamMemberService.isMember(3, 7) } returns true

        mockMvc.get("/teams/3/members/7/exists").andExpect {
            status { isOk() }
            jsonPath("$.exists") { value(true) }
        }
    }

    @Test
    fun `멤버를 내보내면 204를 반환한다`() {
        every { teamMemberService.removeMember(3, 1, 7) } just runs

        mockMvc.delete("/teams/3/members/7").andExpect { status { isNoContent() } }

        verify { teamMemberService.removeMember(3, 1, 7) }
    }
}
