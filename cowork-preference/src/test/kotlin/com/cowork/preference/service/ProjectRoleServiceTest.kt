package com.cowork.preference.service

import com.cowork.preference.domain.ProjectRoleDefinition
import com.cowork.preference.repository.ProjectRoleRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.vertx.core.json.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ProjectRoleServiceTest {

    private val projectId = 3L
    private val roleName = "Reviewer"
    private val permissions = JsonObject().put("issue_write", true)
    private lateinit var repository: ProjectRoleRepository
    private lateinit var service: ProjectRoleService

    @BeforeEach
    fun setUp() {
        repository = mockk(relaxed = true)
        service = ProjectRoleService(repository)
    }

    @Nested
    inner class CreateRole {

        @Test
        fun `같은 이름의 역할이 있으면 중복 역할을 생성하지 않는다`() = runBlocking {
            coEvery { repository.findRole(projectId, roleName) } returns
                ProjectRoleDefinition(projectId, roleName, permissions)

            val result = service.createRole(projectId, roleName, permissions)

            assertTrue(result.exceptionOrNull() is IllegalStateException)
            coVerify(exactly = 0) { repository.insertRole(any(), any(), any()) }
        }

        @Test
        fun `같은 이름의 역할이 없으면 역할을 생성해 반환한다`() = runBlocking {
            coEvery { repository.findRole(projectId, roleName) } returns null

            val result = service.createRole(projectId, roleName, permissions)

            assertEquals(ProjectRoleDefinition(projectId, roleName, permissions), result.getOrThrow())
            coVerify(exactly = 1) { repository.insertRole(projectId, roleName, permissions) }
        }
    }

    @Nested
    inner class DeleteRole {

        @Test
        fun `대상 역할이 없으면 삭제하지 않는다`() = runBlocking {
            coEvery { repository.findRole(projectId, roleName) } returns null

            val result = service.deleteRole(projectId, roleName)

            assertTrue(result.exceptionOrNull() is NoSuchElementException)
            coVerify(exactly = 0) { repository.deleteRole(any(), any()) }
        }
    }

    @Nested
    inner class AssignRole {

        @Test
        fun `대상 역할이 없으면 멤버에게 할당하지 않는다`() = runBlocking {
            coEvery { repository.findRole(projectId, roleName) } returns null

            val result = service.assignRole(11L, projectId, roleName)

            assertTrue(result.exceptionOrNull() is NoSuchElementException)
            coVerify(exactly = 0) { repository.assignRole(any(), any(), any()) }
        }
    }
}
