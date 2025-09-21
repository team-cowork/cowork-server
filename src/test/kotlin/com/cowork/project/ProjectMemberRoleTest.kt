package com.cowork.project

import com.cowork.global.error.BusinessException
import com.cowork.project.domain.Project
import com.cowork.project.domain.ProjectMember
import com.cowork.project.domain.ProjectMemberRole
import com.cowork.project.event.ProjectMemberAddedEvent
import com.cowork.project.repository.ProjectMemberRepository
import com.cowork.project.service.ProjectAccessChecker
import com.cowork.project.service.ProjectMemberService
import com.cowork.team.service.TeamAccessChecker
import com.cowork.user.service.UserService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.HttpStatus

class ProjectMemberRoleTest {

    private val memberRepository = mockk<ProjectMemberRepository>(relaxed = true)
    private val accessChecker = mockk<ProjectAccessChecker>()
    private val teamAccessChecker = mockk<TeamAccessChecker>()
    private val userService = mockk<UserService>(relaxed = true)
    private val eventPublisher = mockk<ApplicationEventPublisher>(relaxed = true)
    private val service = ProjectMemberService(memberRepository, accessChecker, teamAccessChecker, userService, eventPublisher)

    private val project = Project(teamId = 1, name = "앱", description = null, githubRepoUrl = null, position = 0, createdBy = 1, id = 20)

    @BeforeEach
    fun allowManage() {
        every { accessChecker.requireManageable(20, 1) } returns project
    }

    @Test
    fun `이미 참여한 멤버는 다시 추가할 수 없다`() {
        every { teamAccessChecker.isMember(1, 7) } returns true
        every { memberRepository.existsByProjectIdAndUserId(20, 7) } returns true

        val exception = assertThrows<BusinessException> { service.addMember(20, 1, 7, ProjectMemberRole.EDITOR) }

        assertEquals(HttpStatus.CONFLICT, exception.status)
    }

    @Test
    fun `멤버를 추가하면 프로젝트 참여 이벤트를 발행한다`() {
        every { teamAccessChecker.isMember(1, 7) } returns true
        every { memberRepository.existsByProjectIdAndUserId(20, 7) } returns false
        every { memberRepository.save(any()) } answers { firstArg() }

        service.addMember(20, 1, 7, ProjectMemberRole.VIEWER)

        verify { eventPublisher.publishEvent(ProjectMemberAddedEvent(20, "앱", 7, 1)) }
    }

    @Test
    fun `마지막 소유자는 다른 역할로 바꿀 수 없다`() {
        every { memberRepository.findByProjectIdAndUserId(20, 1) } returns ProjectMember(projectId = 20, userId = 1, role = ProjectMemberRole.OWNER)
        every { memberRepository.countByProjectIdAndRole(20, ProjectMemberRole.OWNER) } returns 1

        assertThrows<BusinessException> { service.changeRole(20, 1, 1, ProjectMemberRole.EDITOR) }
    }

    @Test
    fun `소유자가 둘 이상이면 소유자 역할을 내려놓을 수 있다`() {
        val owner = ProjectMember(projectId = 20, userId = 1, role = ProjectMemberRole.OWNER)
        every { memberRepository.findByProjectIdAndUserId(20, 1) } returns owner
        every { memberRepository.countByProjectIdAndRole(20, ProjectMemberRole.OWNER) } returns 2

        service.changeRole(20, 1, 1, ProjectMemberRole.EDITOR)

        assertEquals(ProjectMemberRole.EDITOR, owner.role)
    }

    @Test
    fun `본인은 관리 권한 없이 프로젝트에서 나갈 수 있다`() {
        val member = ProjectMember(projectId = 20, userId = 7, role = ProjectMemberRole.VIEWER)
        every { accessChecker.requireReadable(20, 7) } returns project
        every { memberRepository.findByProjectIdAndUserId(20, 7) } returns member

        service.removeMember(20, 7, 7)

        verify(exactly = 0) { accessChecker.requireManageable(20, 7) }
        verify { memberRepository.delete(member) }
    }
}
