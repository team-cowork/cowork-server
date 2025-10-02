package com.cowork.team

import com.cowork.support.ControllerTestSupport
import com.cowork.team.controller.TeamController
import com.cowork.team.domain.Team
import com.cowork.team.domain.TeamMemberRole
import com.cowork.team.domain.TeamVisibility
import com.cowork.team.dto.TeamResponse
import com.cowork.team.service.TeamService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.post

class TeamControllerTest : ControllerTestSupport() {

    private val teamService = mockk<TeamService>()
    private val mockMvc = mockMvc(TeamController(teamService))

    private val response = TeamResponse.of(
        Team(name = "cowork", description = null, visibility = TeamVisibility.PUBLIC, ownerId = 1, id = 3),
        null,
        1,
        TeamMemberRole.OWNER,
    )

    @Test
    fun `팀을 만들면 201과 팀 정보를 반환한다`() {
        every { teamService.create(1, any()) } returns response

        mockMvc.post("/teams") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"cowork"}"""
        }.andExpect {
            status { isCreated() }
            jsonPath("$.id") { value(3) }
            jsonPath("$.myRole") { value("OWNER") }
        }
    }

    @Test
    fun `팀 이름이 비어 있으면 400을 반환한다`() {
        mockMvc.post("/teams") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":""}"""
        }.andExpect { status { isBadRequest() } }

        verify(exactly = 0) { teamService.create(any(), any()) }
    }
}
