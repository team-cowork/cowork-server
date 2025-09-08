package com.cowork.project

import com.cowork.project.domain.Project
import com.cowork.project.domain.ProjectStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ProjectDomainTest {

    private fun project() = Project(
        teamId = 1, name = "cowork", description = "설명", githubRepoUrl = "https://github.com/team-cowork/app",
        position = 0, createdBy = 1,
    )

    @Test
    fun `빈 저장소 주소로 수정하면 주소를 비운다`() {
        val project = project()

        project.update(null, null, " ", null)

        assertNull(project.githubRepoUrl)
        assertEquals("설명", project.description)
    }

    @Test
    fun `상태만 바꾸면 나머지 항목은 유지한다`() {
        val project = project()

        project.update(null, null, null, ProjectStatus.entries.last())

        assertEquals("cowork", project.name)
        assertEquals(ProjectStatus.entries.last(), project.status)
    }
}
