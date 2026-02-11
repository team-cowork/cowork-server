package com.cowork.team

import com.cowork.support.ControllerTestSupport
import com.cowork.team.controller.TeamRoleController
import com.cowork.team.domain.TeamRole
import com.cowork.team.dto.TeamRoleResponse
import com.cowork.team.service.TeamRoleService
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

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
}
