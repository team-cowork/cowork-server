package com.cowork.project.domain.project.service

import com.cowork.project.domain.github.service.ProjectGithubRepoDeletionSupport
import com.cowork.project.domain.membership.entity.TeamMembership
import com.cowork.project.domain.membership.projection.TeamRoleMemberTombstoneRepository
import com.cowork.project.domain.membership.repository.TeamMembershipRepository
import com.cowork.project.domain.project.event.ProjectEventPublisher
import com.cowork.project.domain.project.entity.Project
import com.cowork.project.domain.project.repository.ProjectRepository
import com.cowork.project.global.consumer.ProjectLifecycleHandler
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Instant
import java.util.Optional

class ProjectMembershipLifecycleTest : DescribeSpec({
    describe("팀 탈퇴와 프로젝트 수명") {
        it("생성자의 탈퇴는 팀 프로젝트를 삭제하지 않고 이전 역할만 차단한다") {
            val projects = mockk<ProjectRepository>(relaxed = true)
            val members = mockk<TeamMembershipRepository>(relaxed = true)
            val fences = mockk<TeamRoleMemberTombstoneRepository>(relaxed = true)
            val events = mockk<ProjectEventPublisher>(relaxed = true)
            val repos = mockk<ProjectGithubRepoDeletionSupport>(relaxed = true)
            val membership = TeamMembership(teamId = 1L, userId = 7L, role = "MEMBER")
            val version = Instant.EPOCH.plusSeconds(1)
            every { members.findStateByTeamIdAndUserIdForUpdate(1L, 7L) } returns membership
            every { fences.findById("1:7") } returns Optional.empty()
            every { fences.save(any()) } answers { firstArg() }
            every { members.save(any()) } answers { firstArg() }
            val handler = ProjectLifecycleHandler(projects, members, events, repos, fences)

            handler.onMemberRemovedFromTeam(1L, 7L, "MEMBER", version)

            membership.active shouldBe false
            verify { fences.save(match { it.sourceOccurredAt == version }) }
            verify(exactly = 0) { projects.deleteAll(any<Iterable<Project>>()) }
            verify(exactly = 0) { repos.deleteByProjectIds(any(), any()) }
        }
        it("늦게 도착한 탈퇴도 재가입 이전 역할의 복구를 막는다") {
            val members = mockk<TeamMembershipRepository>(relaxed = true)
            val fences = mockk<TeamRoleMemberTombstoneRepository>(relaxed = true)
            val version = Instant.EPOCH.plusSeconds(1)
            val membership = TeamMembership(teamId = 1L, userId = 7L, role = "MEMBER", sourceOccurredAt = version.plusSeconds(1))
            every { members.findStateByTeamIdAndUserIdForUpdate(1L, 7L) } returns membership
            every { fences.findById("1:7") } returns Optional.empty()
            every { fences.save(any()) } answers { firstArg() }
            every { members.save(any()) } answers { firstArg() }
            val handler = ProjectLifecycleHandler(mockk(), members, mockk(), mockk(), fences)

            handler.onMemberRemovedFromTeam(1L, 7L, "MEMBER", version)

            membership.active shouldBe true
            verify { fences.save(match { it.sourceOccurredAt == version }) }
            verify(exactly = 0) { members.save(any()) }
        }
    }
})
