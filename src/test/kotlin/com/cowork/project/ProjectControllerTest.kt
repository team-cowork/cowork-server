package com.cowork.project

import com.cowork.project.controller.ProjectController
import com.cowork.project.domain.Project
import com.cowork.project.dto.ProjectResponse
import com.cowork.project.service.ProjectMemberService
import com.cowork.project.service.ProjectService
import com.cowork.support.ControllerTestSupport
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

class ProjectControllerTest : ControllerTestSupport() {

    private val projectService = mockk<ProjectService>()
    private val projectMemberService = mockk<ProjectMemberService>()
    private val mockMvc = mockMvc(ProjectController(projectService, projectMemberService))

    private val project = Project(
        teamId = 1, name = "cowork", description = null, githubRepoUrl = null, position = 0, createdBy = 1, id = 20,
    )

    @Test
    fun `GitHub 저장소 주소 형식이 틀리면 400을 반환한다`() {
        mockMvc.post("/projects") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"teamId":1,"name":"cowork","githubRepoUrl":"https://gitlab.com/a/b"}"""
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `팀 프로젝트 목록은 teamId 쿼리로 조회한다`() {
        every { projectService.getTeamProjects(1, 1) } returns listOf(ProjectResponse.from(project))

        mockMvc.get("/projects") { param("teamId", "1") }.andExpect {
            status { isOk() }
            jsonPath("$[0].id") { value(20) }
        }
    }

    @Test
    fun `teamId 쿼리가 없으면 400을 반환한다`() {
        mockMvc.get("/projects").andExpect { status { isBadRequest() } }
    }

    @Test
    fun `프로젝트를 삭제하면 204를 반환한다`() {
        every { projectService.delete(20, 1) } just runs

        mockMvc.delete("/projects/20").andExpect { status { isNoContent() } }

        verify { projectService.delete(20, 1) }
    }
}
