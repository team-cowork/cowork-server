package com.cowork.project

import com.cowork.project.controller.ProjectController
import com.cowork.project.domain.Project
import com.cowork.project.service.ProjectMemberService
import com.cowork.project.service.ProjectService
import com.cowork.support.ControllerTestSupport
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
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
}
