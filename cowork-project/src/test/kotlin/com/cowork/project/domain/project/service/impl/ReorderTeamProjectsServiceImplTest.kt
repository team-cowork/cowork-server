package com.cowork.project.domain.project.service.impl

import com.cowork.project.domain.membership.entity.TeamMembership
import com.cowork.project.domain.membership.repository.TeamMembershipRepository
import com.cowork.project.domain.project.entity.ProjectAccessProjection
import java.time.Instant
import com.cowork.project.domain.project.entity.Project
import com.cowork.project.domain.project.event.ProjectEventPublisher
import com.cowork.project.domain.project.repository.ProjectRepository
import com.cowork.project.domain.project.service.ProjectAccessGuard
import com.cowork.project.domain.project.repository.ProjectAccessProjectionRepository
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException

class ReorderTeamProjectsServiceImplTest {

    private val projectRepository = mockk<ProjectRepository>(relaxed = true)
    private val teamMembershipRepository = mockk<TeamMembershipRepository>()
    private val accessRepository = mockk<ProjectAccessProjectionRepository>(relaxed = true)
    private val projectAccessGuard =
        ProjectAccessGuard(projectRepository, accessRepository, teamMembershipRepository, mockk(relaxed = true))
    private val projectEventPublisher = mockk<ProjectEventPublisher>(relaxed = true)

    private val service = ReorderTeamProjectsServiceImpl(projectRepository, projectAccessGuard, projectEventPublisher)

    private fun project(id: Long = 1L, teamId: Long = 100L, position: Int = 0) =
        Project(id = id, teamId = teamId, name = "p", description = null, position = position, createdBy = 1L)

    private fun membership(teamId: Long, userId: Long, role: String = "MEMBER") =
        TeamMembership(teamId = teamId, userId = userId, role = role)

    @Nested
    inner class Execute {
        @Test
        fun `reorderTeamProjects는 요청 순서대로 position을 갱신함`() {
            val first = project(id = 1L, position = 0)
            val second = project(id = 2L, position = 1)
            every { teamMembershipRepository.findActiveByTeamIdAndUserId(100L, 7L) } returns membership(100L, 7L, "OWNER")
            every { projectRepository.findAllByTeamIdForUpdate(100L) } returns listOf(first, second)

            val result = service.execute(7L, 100L, listOf(2L, 1L))

            assertEquals(listOf(2L, 1L), result.map { it.id })
            assertEquals(1, first.position)
            assertEquals(0, second.position)
        }

        @Test
        fun `쓰기 권한만으로 재정렬할 수 없고 재정렬 권한만으로는 수정할 수 없다`() {
            val first = project(id = 1L, position = 0)
            val second = project(id = 2L, position = 1)
            every { teamMembershipRepository.findActiveByTeamIdAndUserId(100L, 7L) } returns membership(100L, 7L)
            every { projectRepository.findAllByTeamIdForUpdate(100L) } returns listOf(first, second)
            every { accessRepository.findAllByUserIdAndProjectIdIn(7L, listOf(1L, 2L)) } returns
                listOf(1L, 2L).map { ProjectAccessProjection("$it:7", it, 7L, true, true, Instant.now()) }
            val ex = assertThrows(ExpectedException::class.java) { service.execute(7L, 100L, listOf(2L, 1L)) }
            assertEquals(HttpStatus.FORBIDDEN, ex.statusCode)
            assertEquals(0, first.position)
            assertEquals(1, second.position)

            every { accessRepository.findAllByUserIdAndProjectIdIn(7L, listOf(1L, 2L)) } returns
                listOf(1L, 2L).map { ProjectAccessProjection("$it:7", it, 7L, true, false, Instant.now(), canReorder = true) }
            assertEquals(listOf(2L, 1L), service.execute(7L, 100L, listOf(2L, 1L)).map { it.id })
        }

        @Test
        fun `숨겨진 프로젝트와 움직이지 않는 읽기 전용 프로젝트의 위치를 유지한다`() {
            val first = project(id = 1L, position = 0)
            val hidden = project(id = 2L, position = 1)
            val fixed = project(id = 3L, position = 2)
            val last = project(id = 4L, position = 3)
            every { teamMembershipRepository.findActiveByTeamIdAndUserId(100L, 7L) } returns membership(100L, 7L)
            every { projectRepository.findAllByTeamIdForUpdate(100L) } returns listOf(first, hidden, fixed, last)
            every { accessRepository.findAllByUserIdAndProjectIdIn(7L, listOf(1L, 2L, 3L, 4L)) } returns listOf(
                ProjectAccessProjection("1:7", 1L, 7L, true, false, Instant.now(), canReorder = true),
                ProjectAccessProjection("3:7", 3L, 7L, true, false, Instant.now()),
                ProjectAccessProjection("4:7", 4L, 7L, true, false, Instant.now(), canReorder = true),
            )
            assertEquals(listOf(4L, 3L, 1L), service.execute(7L, 100L, listOf(4L, 3L, 1L)).map { it.id })
            assertEquals(1, hidden.position)
            assertEquals(2, fixed.position)
        }

        @Test
        fun `reorderTeamProjects는 팀 프로젝트 ID 누락 시 BAD_REQUEST`() {
            every { teamMembershipRepository.findActiveByTeamIdAndUserId(100L, 7L) } returns membership(100L, 7L, "OWNER")
            every { projectRepository.findAllByTeamIdForUpdate(100L) } returns
                listOf(project(id = 1L), project(id = 2L))

            val ex = assertThrows(ExpectedException::class.java) {
                service.execute(7L, 100L, listOf(1L))
            }
            assertEquals(HttpStatus.BAD_REQUEST, ex.statusCode)
        }

        @Test
        fun `reorderTeamProjects는 중복 ID가 있으면 BAD_REQUEST`() {
            every { teamMembershipRepository.findActiveByTeamIdAndUserId(100L, 7L) } returns membership(100L, 7L, "OWNER")

            val ex = assertThrows(ExpectedException::class.java) {
                service.execute(7L, 100L, listOf(1L, 1L))
            }

            assertEquals(HttpStatus.BAD_REQUEST, ex.statusCode)
        }
    }
}
