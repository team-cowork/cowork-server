package com.cowork.project.global.consumer

import com.cowork.project.domain.github.service.ProjectGithubRepoDeletionSupport
import com.cowork.project.domain.membership.entity.TeamMembership
import com.cowork.project.domain.membership.repository.TeamMembershipRepository
import com.cowork.project.domain.project.entity.Project
import com.cowork.project.domain.project.event.ProjectEventPublisher
import com.cowork.project.domain.project.repository.ProjectRepository
import com.cowork.project.domain.projectMember.entity.ProjectMember
import com.cowork.project.domain.projectMember.entity.ProjectMemberRole
import com.cowork.project.domain.projectMember.event.ProjectMemberEventPublisher
import com.cowork.project.domain.projectMember.repository.ProjectMemberRepository
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Instant

class ProjectLifecycleHandlerTest {

    private val occurredAt = Instant.parse("2026-08-26T03:00:00Z")

    private val projectRepository = mockk<ProjectRepository>(relaxed = true)
    private val projectMemberRepository = mockk<ProjectMemberRepository>(relaxed = true)
    private val teamMembershipRepository = mockk<TeamMembershipRepository>(relaxed = true)
    private val projectMemberEventPublisher = mockk<ProjectMemberEventPublisher>(relaxed = true)
    private val projectEventPublisher = mockk<ProjectEventPublisher>(relaxed = true)
    private val repoDeletionSupport = mockk<ProjectGithubRepoDeletionSupport>(relaxed = true)

    private val handler = ProjectLifecycleHandler(
        projectRepository,
        projectMemberRepository,
        teamMembershipRepository,
        projectMemberEventPublisher,
        projectEventPublisher,
        repoDeletionSupport,
    )

    init {
        TransactionSynchronizationManager.clear()
        every { teamMembershipRepository.save(any()) } answers { firstArg() }
    }

    private fun project(id: Long, teamId: Long) =
        Project(id = id, teamId = teamId, name = "p$id", description = null, createdBy = 1L)

    @Test
    fun `onTeamDeleted는 팀의 모든 프로젝트를 삭제하고 멤버 캐시 무효화 이벤트를 발행한다`() {
        val projects = listOf(project(1L, 100L), project(2L, 100L))
        every { projectRepository.findAllByTeamIdForUpdate(100L) } returns projects
        every { projectRepository.deleteAll(projects) } just Runs
        val members = listOf(
            ProjectMember(projectId = 1L, userId = 7L, role = ProjectMemberRole.OWNER),
            ProjectMember(projectId = 2L, userId = 8L, role = ProjectMemberRole.EDITOR),
        )
        every { projectMemberRepository.findAllByProjectIdInForUpdate(listOf(1L, 2L)) } returns members

        handler.onTeamDeleted(100L, occurredAt)

        verify(exactly = 1) { projectRepository.deleteAll(projects) }
        verify(exactly = 1) { projectMemberEventPublisher.publishRemoved(members[0], any()) }
        verify(exactly = 1) { projectMemberEventPublisher.publishRemoved(members[1], any()) }
        verify(exactly = 1) { projectEventPublisher.publishDeleted(projects[0], any()) }
        verify(exactly = 1) { projectEventPublisher.publishDeleted(projects[1], any()) }
    }

    @Test
    fun `onTeamDeleted는 대상 없으면 no-op`() {
        every { projectRepository.findAllByTeamIdForUpdate(100L) } returns emptyList()

        handler.onTeamDeleted(100L, occurredAt)

        verify(exactly = 0) { projectRepository.deleteAll(any<List<Project>>()) }
    }

    @Test
    fun `onMemberRemovedFromTeam은 OWNER인 프로젝트는 삭제, 나머지는 멤버십만 제거하며 각각 캐시 무효화 이벤트를 발행한다`() {
        val projects = listOf(project(1L, 100L), project(2L, 100L), project(3L, 100L))
        val ownerMembership = ProjectMember(projectId = 2L, userId = 7L, role = ProjectMemberRole.OWNER)
        val ownerProjectMembers = listOf(
            ProjectMember(projectId = 2L, userId = 7L, role = ProjectMemberRole.OWNER),
            ProjectMember(projectId = 2L, userId = 9L, role = ProjectMemberRole.EDITOR),
        )
        every { projectRepository.findAllByTeamIdForUpdate(100L) } returns projects
        every {
            projectMemberRepository.findAllByUserIdAndProjectIdInForUpdate(7L, listOf(1L, 2L, 3L))
        } returns listOf(ownerMembership)
        every { projectMemberRepository.findAllByProjectIdInForUpdate(listOf(2L)) } returns ownerProjectMembers
        val membership = TeamMembership(teamId = 100L, userId = 7L, role = "MEMBER")
        every { teamMembershipRepository.findStateByTeamIdAndUserIdForUpdate(100L, 7L) } returns membership

        handler.onMemberRemovedFromTeam(100L, 7L, "MEMBER", occurredAt)

        verify(exactly = 1) { teamMembershipRepository.save(membership) }
        verify(exactly = 1) { projectRepository.deleteAll(listOf(projects[1])) }
        verify(exactly = 1) { projectMemberEventPublisher.publishRemoved(ownerProjectMembers[0], any()) }
        verify(exactly = 1) { projectMemberEventPublisher.publishRemoved(ownerProjectMembers[1], any()) }
        verify(exactly = 1) { projectMemberEventPublisher.publishRemoved(1L, 7L, any()) }
        verify(exactly = 1) { projectMemberEventPublisher.publishRemoved(3L, 7L, any()) }
        verify(exactly = 1) { projectEventPublisher.publishDeleted(match { it.id == 2L }, any()) }
    }

    @Test
    fun `stale upsert는 최신 delete tombstone을 되살리지 않는다`() {
        val membership = TeamMembership(
            teamId = 100L,
            userId = 7L,
            role = "MEMBER",
            active = false,
            sourceOccurredAt = occurredAt,
        )
        every { teamMembershipRepository.findStateByTeamIdAndUserIdForUpdate(100L, 7L) } returns membership

        handler.onMemberUpsert(100L, 7L, "ADMIN", occurredAt.minusSeconds(1))

        verify(exactly = 0) { teamMembershipRepository.save(any()) }
    }

    @Test
    fun `같은 DB microsecond의 upsert는 delete tombstone을 되살리지 않는다`() {
        val deletedAt = Instant.parse("2026-08-26T03:00:00.123456Z")
        val membership = TeamMembership(
            teamId = 100L,
            userId = 7L,
            role = "MEMBER",
            active = false,
            sourceOccurredAt = deletedAt,
        )
        every { teamMembershipRepository.findStateByTeamIdAndUserIdForUpdate(100L, 7L) } returns membership

        handler.onMemberUpsert(100L, 7L, "ADMIN", Instant.parse("2026-08-26T03:00:00.123456999Z"))

        verify(exactly = 0) { teamMembershipRepository.save(any()) }
        assertEquals(false, membership.active)
        assertEquals(deletedAt, membership.sourceOccurredAt)
    }
}
