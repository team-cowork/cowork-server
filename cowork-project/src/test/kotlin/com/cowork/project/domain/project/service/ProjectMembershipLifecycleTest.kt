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
    describe("팀 멤버 권한 버전") {
        it("같은 원본 버전의 상충하는 역할로 관리자 권한을 복원하지 않는다") {
            val version = Instant.EPOCH.plusSeconds(2)
            val member = TeamMembership(
                teamId = 1L, userId = 7L, role = "MEMBER", sourceOccurredAt = version,
                authorizationVersion = version,
            )
            val members = mockk<TeamMembershipRepository>(relaxed = true)
            every { members.findStateByTeamIdAndUserIdForUpdate(1L, 7L) } returns member
            val handler = ProjectLifecycleHandler(mockk(), members, mockk(), mockk(), mockk())

            handler.onMemberUpsert(1L, 7L, "ADMIN", version, version.minusSeconds(1))

            member.role shouldBe "MEMBER"
            member.authorizationVersion shouldBe version
            verify(exactly = 0) { members.save(any()) }
        }

        it("닉네임 변경은 권한 버전을 유지하고 같은 이벤트의 구형 재전송은 이를 지우지 않는다") {
            val authorizationVersion = Instant.EPOCH.plusSeconds(1)
            val sourceVersion = authorizationVersion.plusSeconds(1)
            val member = TeamMembership(
                teamId = 1L, userId = 7L, role = "MEMBER", sourceOccurredAt = authorizationVersion,
                authorizationVersion = authorizationVersion,
            )

            member.applyUpsert("MEMBER", sourceVersion, authorizationVersion)
            member.applyUpsert("MEMBER", sourceVersion)

            member.sourceOccurredAt shouldBe sourceVersion
            member.authorizationVersion shouldBe authorizationVersion
        }
        it("더 새로운 구형 이벤트는 권한 변경 여부를 알 수 없어 과거 권한 버전을 보존하지 않는다") {
            val version = Instant.EPOCH.plusSeconds(1)
            val member = TeamMembership(
                teamId = 1L, userId = 7L, role = "ADMIN", sourceOccurredAt = version,
                authorizationVersion = version,
            )

            member.applyUpsert("MEMBER", version.plusSeconds(1))

            member.authorizationVersion shouldBe null
            member.sourceOccurredAt shouldBe version.plusSeconds(1)
        }
        it("같은 원본 버전의 권한 정보는 보정하되 오래된 권한 버전으로 되돌리지 않는다") {
            val version = Instant.EPOCH.plusSeconds(2)
            val member = TeamMembership(teamId = 1L, userId = 7L, role = "MEMBER", sourceOccurredAt = version)

            member.applyUpsert("MEMBER", version, version)
            member.applyUpsert("MEMBER", version, version.minusSeconds(1))

            member.authorizationVersion shouldBe version
        }
    }

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
