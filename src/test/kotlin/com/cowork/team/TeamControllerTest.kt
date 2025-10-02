package com.cowork.team

import com.cowork.global.error.BusinessException
import com.cowork.support.ControllerTestSupport
import com.cowork.team.controller.TeamController
import com.cowork.team.domain.Team
import com.cowork.team.domain.TeamMemberRole
import com.cowork.team.domain.TeamVisibility
import com.cowork.team.dto.TeamResponse
import com.cowork.team.service.TeamService
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
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

    @Test
    fun `서비스 예외는 상태 코드와 메시지로 변환한다`() {
        every { teamService.getTeam(3, 1) } throws BusinessException(HttpStatus.FORBIDDEN, "비공개 팀입니다.")

        mockMvc.get("/teams/3").andExpect {
            status { isForbidden() }
            jsonPath("$.message") { value("비공개 팀입니다.") }
        }
    }

    @Test
    fun `팀 ID가 숫자가 아니면 400을 반환한다`() {
        mockMvc.get("/teams/abc").andExpect { status { isBadRequest() } }
    }

    @Test
    fun `팀 이름은 100자를 넘을 수 없다`() {
        mockMvc.patch("/teams/3") {
            contentType = MediaType.APPLICATION_JSON
            content = json(mapOf("name" to "a".repeat(101)))
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `팀을 삭제하면 204를 반환한다`() {
        every { teamService.delete(3, 1) } just runs

        mockMvc.delete("/teams/3").andExpect { status { isNoContent() } }

        verify { teamService.delete(3, 1) }
    }
}
