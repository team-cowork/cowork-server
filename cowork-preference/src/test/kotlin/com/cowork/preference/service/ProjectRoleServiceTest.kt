package com.cowork.preference.service

import com.cowork.preference.domain.ProjectRoleDefinition
import com.cowork.preference.domain.ProjectLifecycleProjection
import com.cowork.preference.domain.ProjectPermissions
import com.cowork.preference.domain.TeamMemberProjection
import com.cowork.preference.domain.TeamRoleDefinition
import com.cowork.preference.messaging.ProjectionReadinessView
import com.cowork.preference.repository.InitialResourceAccessRepository
import com.cowork.preference.repository.PreferenceOutboxRepository
import com.cowork.preference.repository.ProjectAccessStateRepository
import com.cowork.preference.repository.ProjectLifecycleProjectionRepository
import com.cowork.preference.repository.ProjectRoleRepository
import com.cowork.preference.repository.TeamMemberProjectionRepository
import com.cowork.preference.repository.TeamRoleRepository
import com.cowork.preference.security.RequesterContext
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Pool
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant

class ProjectRoleServiceTest {

    private val projectId = 3L
    private val roleName = "Reviewer"
    private val permissions = JsonObject().put("write", true)
    private val requester = RequesterContext(217L, "MEMBER")
    private val targetId = 218L
    private val teamId = 5L
    private lateinit var repository: ProjectRoleRepository
    private lateinit var service: ProjectRoleService
    private lateinit var guard: ProjectAccessGuard
    private lateinit var teamRoles: TeamRoleRepository
    private lateinit var initialAccess: InitialResourceAccessRepository
    private lateinit var connection: SqlConnection
    private lateinit var outbox: PreferenceOutboxRepository
    private val access = mockk<ProjectAccessStateRepository>(relaxed = true)

    @BeforeEach
    fun setUp() {
        repository = mockk(relaxed = true)
        outbox = mockk()
        connection = mockk()
        initialAccess = mockk(relaxed = true)
        val pool = mockk<Pool>()
        val projects = mockk<ProjectLifecycleProjectionRepository>()
        val members = mockk<TeamMemberProjectionRepository>()
        val readiness = mockk<ProjectionReadinessView>()
        teamRoles = mockk()
        every { readiness.isReady } returns true
        coEvery { projects.findForRead(any(), projectId) } returns
            ProjectLifecycleProjection(projectId, teamId, false, Instant.EPOCH)
        coEvery { members.findForRead(any(), teamId, any()) } coAnswers {
            TeamMemberProjection(teamId, arg(2), "MEMBER", false, Instant.EPOCH)
        }
        coEvery { repository.effectivePermissions(projectId, requester.userId, any()) } returns
            ProjectPermissions(read = true, write = true)
        coEvery { repository.findMemberRoles(projectId, any()) } returns emptyList()
        coEvery { teamRoles.findAccountRoles(any(), teamId, requester.userId) } returns listOf(
            TeamRoleDefinition(1, teamId, "manager", "#99AAB5", 10, false, mapOf("manage_roles" to true), null, null),
        )
        coEvery { teamRoles.findAccountRoles(any(), teamId, targetId) } returns listOf(
            TeamRoleDefinition(2, teamId, "member", "#99AAB5", 5, false, emptyMap(), null, null),
        )
        guard = ProjectAccessGuard(pool, projects, members, readiness, repository, teamRoles)
        coEvery { outbox.inTransaction<Unit>(any<suspend (SqlConnection) -> Unit>()) } coAnswers {
            firstArg<suspend (SqlConnection) -> Unit>().invoke(connection)
        }
        service = ProjectRoleService(repository, outbox, access, initialAccess, guard)
    }

    @Nested
    inner class CreateRole {

        @Test
        fun `같은 이름의 역할이 있으면 중복 역할을 생성하지 않는다`() = runBlocking {
            coEvery { repository.findRole(projectId, roleName, any()) } returns
                ProjectRoleDefinition(projectId, roleName, permissions)

            val result = service.createRole(projectId, roleName, permissions, requester)

            assertTrue(result.exceptionOrNull() is IllegalStateException)
            coVerify(exactly = 0) { repository.insertRole(any(), any(), any(), any(), any()) }
        }

        @Test
        fun `같은 이름의 역할이 없으면 역할을 생성해 반환한다`() = runBlocking {
            coEvery { repository.findRole(projectId, roleName, any()) } returns null

            val result = service.createRole(projectId, roleName, permissions, requester)

            assertEquals(ProjectRoleDefinition(projectId, roleName, permissions), result.getOrThrow())
            coVerify(exactly = 1) { repository.insertRole(projectId, roleName, permissions, 0, any()) }
        }
    }

    @Test
    fun `역할 부여와 회수는 같은 트랜잭션에서 접근 권한을 다시 계산한다`() = runBlocking {
        coEvery { repository.findRole(projectId, roleName, any()) } returns ProjectRoleDefinition(projectId, roleName, permissions, 20)
        service.assignRole(targetId, projectId, roleName, requester).getOrThrow()
        service.removeRole(targetId, projectId, roleName, requester)
        coVerify(exactly = 2) { access.refresh(any(), projectId) }
        coVerify { repository.assignRole(targetId, projectId, roleName, any()) }
        coVerify { repository.removeRole(targetId, projectId, roleName, any()) }
    }

    @Nested
    inner class DeleteRole {

        @Test
        fun `대상 역할이 없으면 삭제하지 않는다`() = runBlocking {
            coEvery { repository.findRole(projectId, roleName, any()) } returns null

            val result = service.deleteRole(projectId, roleName, requester)

            assertTrue(result.exceptionOrNull() is NoSuchElementException)
            coVerify(exactly = 0) { repository.deleteRole(any(), any(), any()) }
        }
    }

    @Nested
    inner class AssignRole {

        @Test
        fun `대상 역할이 없으면 멤버에게 할당하지 않는다`() = runBlocking {
            coEvery { repository.findRole(projectId, roleName, any()) } returns null

            val result = service.assignRole(targetId, projectId, roleName, requester)

            assertTrue(result.exceptionOrNull() is NoSuchElementException)
            coVerify(exactly = 0) { repository.assignRole(any(), any(), any(), any()) }
        }
    }

    @Test
    fun `역할 해제 실행 전에 같은 이름으로 거부 역할이 생기면 본인 역할도 제거하지 않는다`() = runBlocking {
        coEvery { repository.findRole(projectId, roleName, any()) } returns null
        guard.requireRoleRemoval(projectId, requester, roleName, requester.userId)
        replaceRoleOnTransactionEntry(JsonObject().put("read", false))

        assertThrows<ResourceAccessDeniedException> {
            service.removeRole(requester.userId, projectId, roleName, requester)
        }

        coVerify(exactly = 0) { repository.removeRole(any(), any(), any(), any()) }
        coVerify(exactly = 0) { initialAccess.claim(any(), any(), any()) }
    }

    @Test
    fun `역할 삭제 실행 전에 허용 역할이 거부 역할로 바뀌면 삭제하지 않는다`() = runBlocking {
        coEvery { repository.findRole(projectId, roleName, any()) } returns
            ProjectRoleDefinition(projectId, roleName, permissions)
        guard.requireRoleDeletion(projectId, requester, roleName)
        replaceRoleOnTransactionEntry(JsonObject().put("write", false))

        val result = service.deleteRole(projectId, roleName, requester)

        assertTrue(result.exceptionOrNull() is ResourceAccessDeniedException)
        coVerify(exactly = 0) { repository.deleteRole(any(), any(), any()) }
        coVerify(exactly = 0) { initialAccess.claim(any(), any(), any()) }
    }

    @Test
    fun `역할 제거 실행 전에 역할 관리 권한을 잃으면 본인 역할도 제거하지 않는다`() = runBlocking {
        coEvery { repository.findRole(projectId, roleName, any()) } returns
            ProjectRoleDefinition(projectId, roleName, permissions)
        guard.requireRoleRemoval(projectId, requester, roleName, requester.userId)
        coEvery { outbox.inTransaction<Unit>(any<suspend (SqlConnection) -> Unit>()) } coAnswers {
            coEvery { teamRoles.findAccountRoles(connection, teamId, requester.userId) } returns emptyList()
            firstArg<suspend (SqlConnection) -> Unit>().invoke(connection)
        }

        assertThrows<ResourceAccessDeniedException> {
            service.removeRole(requester.userId, projectId, roleName, requester)
        }

        coVerify(exactly = 0) { repository.removeRole(any(), any(), any(), any()) }
        coVerify(exactly = 0) { initialAccess.claim(any(), any(), any()) }
    }

    private fun replaceRoleOnTransactionEntry(currentPermissions: JsonObject) {
        coEvery { outbox.inTransaction<Unit>(any<suspend (SqlConnection) -> Unit>()) } coAnswers {
            coEvery { repository.findRole(projectId, roleName, connection) } returns
                ProjectRoleDefinition(projectId, roleName, currentPermissions)
            firstArg<suspend (SqlConnection) -> Unit>().invoke(connection)
        }
    }

    @Test
    fun `역할 할당 실행 전에 더 높은 권한의 역할로 교체되면 할당하지 않는다`() = runBlocking {
        coEvery { repository.findRole(projectId, roleName, any()) } returns
            ProjectRoleDefinition(projectId, roleName, permissions)
        guard.requireRoleAssignment(projectId, requester, roleName, targetId)
        replaceRoleOnTransactionEntry(JsonObject().put("manage", true))

        val result = service.assignRole(targetId, projectId, roleName, requester)

        assertTrue(result.exceptionOrNull() is ResourceAccessDeniedException)
        coVerify(exactly = 0) { repository.assignRole(any(), any(), any(), any()) }
        coVerify(exactly = 0) { initialAccess.claim(any(), any(), any()) }
    }

    @Test
    fun `역할 생성 실행 전에 권한을 잃으면 이미 승인했던 역할도 생성하지 않는다`() = runBlocking {
        coEvery { repository.findRole(projectId, roleName, any()) } returns null
        guard.requireRoleCreation(projectId, requester, permissions)
        coEvery { outbox.inTransaction<Unit>(any<suspend (SqlConnection) -> Unit>()) } coAnswers {
            coEvery { repository.effectivePermissions(projectId, requester.userId, connection) } returns
                ProjectPermissions(read = true)
            firstArg<suspend (SqlConnection) -> Unit>().invoke(connection)
        }

        val result = service.createRole(projectId, roleName, permissions, requester)

        assertTrue(result.exceptionOrNull() is ResourceAccessDeniedException)
        coVerify(exactly = 0) { repository.insertRole(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { initialAccess.claim(any(), any(), any()) }
    }
}
