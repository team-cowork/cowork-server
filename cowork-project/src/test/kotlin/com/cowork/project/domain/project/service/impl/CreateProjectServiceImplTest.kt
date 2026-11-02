package com.cowork.project.domain.project.service.impl

import com.cowork.project.domain.membership.entity.TeamMembership
import com.cowork.project.domain.membership.TeamRolePermissionReader
import com.cowork.project.domain.membership.repository.TeamMembershipRepository
import com.cowork.project.domain.project.entity.Project
import com.cowork.project.domain.project.event.ProjectEventPublisher
import com.cowork.project.domain.project.presentation.data.request.CreateProjectReqDto
import com.cowork.project.domain.project.repository.ProjectRepository
import com.cowork.project.domain.project.service.ProjectAccessGuard
import com.cowork.project.domain.project.repository.ProjectAccessProjectionRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException

class CreateProjectServiceImplTest {

    private val projectRepository = mockk<ProjectRepository>(relaxed = true)
    private val teamMembershipRepository = mockk<TeamMembershipRepository>()
    private val projectEventPublisher = mockk<ProjectEventPublisher>(relaxed = true)
    private val teamPermissions = mockk<TeamRolePermissionReader>(relaxed = true)
    private val projectAccessGuard =
        ProjectAccessGuard(projectRepository, mockk<ProjectAccessProjectionRepository>(relaxed = true), teamMembershipRepository, mockk(relaxed = true), teamPermissions)

    private val service =
        CreateProjectServiceImpl(
            projectRepository,
            projectEventPublisher,
            projectAccessGuard,
        )

    private fun membership(teamId: Long, userId: Long, role: String = "MEMBER") =
        TeamMembership(teamId = teamId, userId = userId, role = role)

    private fun project(id: Long, position: Int) =
        Project(id = id, teamId = 100L, name = "p", description = null, position = position, createdBy = 1L)

    @Nested
    inner class Execute {
        @Test
        fun `createProject은 팀 멤버 아니면 FORBIDDEN`() {
            every { teamMembershipRepository.findActiveByTeamIdAndUserId(100L, 7L) } returns null

            val ex = assertThrows(ExpectedException::class.java) {
                service.execute(7L, CreateProjectReqDto(teamId = 100L, name = "p", description = null))
            }
            assertEquals(HttpStatus.FORBIDDEN, ex.statusCode)
            verify(exactly = 0) { projectRepository.save(any()) }
        }

        @Test
        fun `createProject은 잘못된 이름이어도 권한 확인을 먼저 수행해 FORBIDDEN`() {
            every { teamMembershipRepository.findActiveByTeamIdAndUserId(100L, 7L) } returns null

            val ex = assertThrows(ExpectedException::class.java) {
                service.execute(7L, CreateProjectReqDto(teamId = 100L, name = "", description = null))
            }

            assertEquals(HttpStatus.FORBIDDEN, ex.statusCode)
        }

        @Test
        fun `createProject은 이름이 비었거나 공백뿐이거나 100자를 넘으면 쓰기 작업 없이 BAD_REQUEST`() {
            every { teamMembershipRepository.findActiveByTeamIdAndUserId(100L, 7L) } returns membership(100L, 7L, "OWNER")

            listOf("", "   ", "\t\n", "가".repeat(101)).forEach { name ->
                val ex = assertThrows(ExpectedException::class.java) {
                    service.execute(7L, CreateProjectReqDto(teamId = 100L, name = name, description = null))
                }

                assertEquals(HttpStatus.BAD_REQUEST, ex.statusCode)
            }

            verify(exactly = 0) { projectRepository.findAllByTeamIdForUpdate(any()) }
            verify(exactly = 0) { projectRepository.save(any()) }
            verify(exactly = 0) { projectEventPublisher.publishCreated(any(), any()) }
        }

        @Test
        fun `createProject은 유효한 이름을 입력값 그대로 저장한다`() {
            every { teamMembershipRepository.findActiveByTeamIdAndUserId(100L, 7L) } returns membership(100L, 7L, "OWNER")
            every { projectRepository.findAllByTeamIdForUpdate(100L) } returns emptyList()
            every { projectRepository.save(any()) } answers { firstArg() }

            listOf("가", "가".repeat(100), "개발 팀", " 개발 팀 ").forEach { name ->
                val response = service.execute(7L, CreateProjectReqDto(teamId = 100L, name = name, description = null))

                assertEquals(name, response.name)
            }
        }

        @Test
        fun `createProject은 마지막 position 다음에 만들고 권한 기반 접근 목록 갱신을 요청한다`() {
            every { teamMembershipRepository.findActiveByTeamIdAndUserId(100L, 7L) } returns membership(100L, 7L, "OWNER")
            every { projectRepository.findAllByTeamIdForUpdate(100L) } returns
                listOf(project(id = 1L, position = 3), project(id = 2L, position = 1))
            every { projectRepository.save(any()) } answers { firstArg() }

            val response = service.execute(7L, CreateProjectReqDto(teamId = 100L, name = "p", description = null))

            assertEquals(4, response.position)
            assertEquals(false, response.isPrivate)
        }

        @Test
        fun `createProject은 프로젝트가 없는 팀에 position 0을 할당한다`() {
            every { teamMembershipRepository.findActiveByTeamIdAndUserId(100L, 7L) } returns membership(100L, 7L, "OWNER")
            every { projectRepository.findAllByTeamIdForUpdate(100L) } returns emptyList()
            every { projectRepository.save(any()) } answers { firstArg() }

            val response = service.execute(7L, CreateProjectReqDto(teamId = 100L, name = "p", description = null))

            assertEquals(0, response.position)
        }

        @Test
        fun `createProject은 동시 생성으로 position 유일성 제약이 충돌하면 CONFLICT`() {
            every { teamMembershipRepository.findActiveByTeamIdAndUserId(100L, 7L) } returns membership(100L, 7L, "OWNER")
            every { projectRepository.findAllByTeamIdForUpdate(100L) } returns emptyList()
            every { projectRepository.save(any()) } throws DataIntegrityViolationException("uq_tb_projects_team_id_position")

            val ex = assertThrows(ExpectedException::class.java) {
                service.execute(7L, CreateProjectReqDto(teamId = 100L, name = "p", description = null))
            }

            assertEquals(HttpStatus.CONFLICT, ex.statusCode)
            verify(exactly = 0) { projectEventPublisher.publishCreated(any(), any()) }
        }

        @Test
        fun `createProject은 일반 멤버의 비공개 생성 승인을 초기 읽기 역할 요청으로 보존한다`() {
            val version = Instant.parse("2026-10-25T00:00:00Z")
            val authorizationVersion = version.minusSeconds(1)
            val saved = slot<Project>()
            every { teamMembershipRepository.findActiveByTeamIdAndUserId(100L, 7L) } returns
                TeamMembership(teamId = 100L, userId = 7L, role = "MEMBER", sourceOccurredAt = version, authorizationVersion = authorizationVersion)
            every { teamPermissions.hasPermission(100L, 7L, "manage_projects") } returns true
            every { teamPermissions.hasPermission(100L, 7L, "project_read") } returns true
            every { projectRepository.save(capture(saved)) } answers { firstArg() }

            val response = service.execute(7L, CreateProjectReqDto(teamId = 100L, name = "private", isPrivate = true))

            assertEquals(true, response.isPrivate)
            assertEquals(7L, saved.captured.createdBy)
            assertEquals(version, saved.captured.initialReadMembershipVersion)
            assertEquals(authorizationVersion, saved.captured.initialReadAuthorizationVersion)
            verify { projectEventPublisher.publishCreated(saved.captured, any()) }
        }
    }
}
