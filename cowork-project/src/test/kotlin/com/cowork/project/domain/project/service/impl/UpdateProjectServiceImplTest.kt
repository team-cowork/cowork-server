package com.cowork.project.domain.project.service.impl

import com.cowork.project.domain.membership.entity.TeamMembership
import com.cowork.project.domain.membership.repository.TeamMembershipRepository
import com.cowork.project.domain.project.entity.Project
import com.cowork.project.domain.project.entity.ProjectAccessProjection
import com.cowork.project.domain.project.event.ProjectEventPublisher
import com.cowork.project.domain.project.presentation.data.request.UpdateProjectReqDto
import com.cowork.project.domain.project.repository.ProjectRepository
import com.cowork.project.domain.project.service.ProjectAccessGuard
import com.cowork.project.domain.project.repository.ProjectAccessProjectionRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Instant
import java.util.Optional
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException

class UpdateProjectServiceImplTest {

    private val projectRepository = mockk<ProjectRepository>(relaxed = true)
    private val teamMembershipRepository = mockk<TeamMembershipRepository>()
    private val projectEventPublisher = mockk<ProjectEventPublisher>(relaxed = true)
    private val accessRepository = mockk<ProjectAccessProjectionRepository>(relaxed = true)
    private val projectAccessGuard =
        ProjectAccessGuard(projectRepository, accessRepository, teamMembershipRepository, mockk(relaxed = true), mockk(relaxed = true))

    private val service = UpdateProjectServiceImpl(projectEventPublisher, projectAccessGuard)

    private fun project(id: Long = 1L, teamId: Long = 100L, position: Int = 0) =
        Project(id = id, teamId = teamId, name = "p", description = null, position = position, createdBy = 1L)

    private fun membership(teamId: Long, userId: Long, role: String = "MEMBER") =
        TeamMembership(teamId = teamId, userId = userId, role = role)

    @Nested
    inner class Execute {
        @Test
        fun `updateProject은 팀 OWNER 등가 권한으로 통과`() {
            val proj = project()
            every { projectRepository.findByIdForUpdate(1L) } returns proj

            every { teamMembershipRepository.findActiveByTeamIdAndUserId(100L, 99L) } returns
                membership(100L, 99L, "OWNER")

            val response = service.execute(99L, 1L, UpdateProjectReqDto(name = "newName", isPrivate = true))
            assertEquals("newName", response.name)
            assertEquals(true, response.isPrivate)
        }

        @Test
        fun `updateProject은 팀 비멤버이면 FORBIDDEN`() {
            val proj = project()
            every { projectRepository.findByIdForUpdate(1L) } returns proj

            every { teamMembershipRepository.findActiveByTeamIdAndUserId(100L, 99L) } returns null

            val ex = assertThrows(ExpectedException::class.java) {
                service.execute(99L, 1L, UpdateProjectReqDto(name = "x"))
            }
            assertEquals(HttpStatus.FORBIDDEN, ex.statusCode)
        }

        @Test
        fun `일반 관리자가 비공개 전환 후 접근을 잃으면 변경 전에 거절한다`() {
            val proj = project()
            every { projectRepository.findByIdForUpdate(1L) } returns proj
            every { teamMembershipRepository.findActiveByTeamIdAndUserId(100L, 99L) } returns membership(100L, 99L)
            every { accessRepository.findById("1:99") } returns Optional.of(
                ProjectAccessProjection("1:99", 1L, 99L, true, false, Instant.now(), canManage = true, canReadPrivate = false),
            )

            val ex = assertThrows(ExpectedException::class.java) {
                service.execute(99L, 1L, UpdateProjectReqDto(name = "changed", isPrivate = true))
            }

            assertEquals(HttpStatus.BAD_REQUEST, ex.statusCode)
            assertEquals("p", proj.name)
            assertEquals(false, proj.isPrivate)
            verify(exactly = 0) { projectEventPublisher.publishUpdated(any(), any()) }
        }

        @Test
        fun `비공개에서도 읽기 가능한 일반 관리자는 비공개로 전환한다`() {
            val proj = project()
            every { projectRepository.findByIdForUpdate(1L) } returns proj
            every { teamMembershipRepository.findActiveByTeamIdAndUserId(100L, 99L) } returns membership(100L, 99L)
            every { accessRepository.findById("1:99") } returns Optional.of(
                ProjectAccessProjection("1:99", 1L, 99L, true, false, Instant.now(), canManage = true, canReadPrivate = true),
            )

            val response = service.execute(99L, 1L, UpdateProjectReqDto(name = "changed", isPrivate = true))

            assertEquals(true, response.isPrivate)
            assertEquals("changed", response.name)
            verify { projectEventPublisher.publishUpdated(proj, any()) }
        }

        @Test
        fun `이미 비공개인 프로젝트의 설정 변경에는 비공개 전환 조건을 추가하지 않는다`() {
            val proj = project().apply { isPrivate = true }
            every { projectRepository.findByIdForUpdate(1L) } returns proj
            every { teamMembershipRepository.findActiveByTeamIdAndUserId(100L, 99L) } returns membership(100L, 99L)
            every { accessRepository.findById("1:99") } returns Optional.of(
                ProjectAccessProjection("1:99", 1L, 99L, true, false, Instant.now(), canManage = true),
            )

            val response = service.execute(99L, 1L, UpdateProjectReqDto(name = "changed", isPrivate = true))

            assertEquals("changed", response.name)
        }
    }
}
