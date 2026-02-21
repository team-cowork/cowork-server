package com.cowork.team

import com.cowork.support.ControllerTestSupport
import com.cowork.team.controller.TeamRoleController
import com.cowork.team.domain.TeamRole
import com.cowork.team.dto.TeamRoleResponse
import com.cowork.team.service.TeamRoleService
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put

class TeamRoleControllerTest : ControllerTestSupport() {

    private val teamRoleService = mockk<TeamRoleService>()
    private val mockMvc = mockMvc(TeamRoleController(teamRoleService))

    @Test
    fun `색상 코드 형식이 틀리면 400을 반환한다`() {
        mockMvc.post("/teams/3/roles") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"디자인","colorHex":"red"}"""
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `역할 목록을 반환한다`() {
        every { teamRoleService.getRoles(3, 1) } returns
            listOf(TeamRoleResponse.from(TeamRole(teamId = 3, name = "디자인", id = 30)))

        mockMvc.get("/teams/3/roles").andExpect {
            status { isOk() }
            jsonPath("$[0].name") { value("디자인") }
            jsonPath("$[0].colorHex") { value(TeamRole.DEFAULT_COLOR) }
        }
    }

    @Test
    fun `역할을 부여하면 204를 반환한다`() {
        every { teamRoleService.assign(3, 1, 7, 30) } just runs

        mockMvc.put("/teams/3/members/7/roles/30").andExpect { status { isNoContent() } }
    }

    @Test
    fun `역할을 회수하면 204를 반환한다`() {
        every { teamRoleService.unassign(3, 1, 7, 30) } just runs

        mockMvc.delete("/teams/3/members/7/roles/30").andExpect { status { isNoContent() } }

        verify { teamRoleService.unassign(3, 1, 7, 30) }
    }
}
