package com.cowork.project.domain.project.service

import com.cowork.project.domain.membership.entity.TeamMembership
import com.cowork.project.domain.membership.repository.TeamMembershipRepository
import com.cowork.project.domain.project.entity.Project
import com.cowork.project.domain.project.entity.ProjectAccessProjection
import com.cowork.project.domain.project.repository.ProjectAccessProjectionRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import java.util.Optional
import team.themoment.sdk.exception.ExpectedException

class ProjectAccessGuardTest : DescribeSpec({
    val project = Project(id = 3L, teamId = 1L, name = "p", description = null, createdBy = 10L)
    val members = mockk<TeamMembershipRepository>()
    val access = mockk<ProjectAccessProjectionRepository>()
    val guard = ProjectAccessGuard(mockk(), access, members, mockk(relaxed = true))
    beforeEach {
        every { members.findActiveByTeamIdAndUserId(1L, 20L) } returns TeamMembership(teamId = 1L, userId = 20L, role = "MEMBER")
        every { access.findById("3:20") } returns Optional.empty()
    }
    describe("프로젝트 접근 권한") {
        it("팀 가입만으로 기본 거부를 우회하지 않는다") {
            guard.canRead(project, 20L) shouldBe false
            shouldThrow<ExpectedException> { guard.requireProjectReader(project, 20L) }
        }
        it("read만 부여하면 조회는 허용하고 수정은 거부한다") {
            every { access.findById("3:20") } returns Optional.of(ProjectAccessProjection("3:20", 3L, 20L, true, false, Instant.now()))
            guard.requireProjectReader(project, 20L)
            shouldThrow<ExpectedException> { guard.requireProjectModifier(project, 20L) }
        }
        it("read와 write를 부여하면 별도 멤버 등록 없이 수정한다") {
            every { access.findById("3:20") } returns Optional.of(ProjectAccessProjection("3:20", 3L, 20L, true, true, Instant.now()))
            guard.requireProjectModifier(project, 20L)
            shouldThrow<ExpectedException> { guard.requireProjectOwner(project, 20L) }
        }
        it("회수된 read는 write가 남아도 접근을 허용하지 않는다") {
            every { access.findById("3:20") } returns Optional.of(ProjectAccessProjection("3:20", 3L, 20L, false, true, Instant.now()))
            guard.canRead(project, 20L) shouldBe false
            guard.canWrite(project, 20L) shouldBe false
        }
        it("탈퇴한 사용자에게 남은 권한으로 접근하지 않는다") {
            every { members.findActiveByTeamIdAndUserId(1L, 20L) } returns null
            guard.canRead(project, 20L) shouldBe false
        }
        it("팀 OWNER는 프로젝트를 관리할 수 있다") {
            every { members.findActiveByTeamIdAndUserId(1L, 20L) } returns TeamMembership(teamId = 1L, userId = 20L, role = "OWNER")
            guard.requireProjectOwner(project, 20L)
            guard.requireProjectModifier(project, 20L)
        }
    }
})
