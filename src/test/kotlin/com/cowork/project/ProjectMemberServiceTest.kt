package com.cowork.project

import com.cowork.global.error.BusinessException
import com.cowork.project.domain.Project
import com.cowork.project.domain.ProjectMember
import com.cowork.project.domain.ProjectMemberRole
import com.cowork.project.repository.ProjectMemberRepository
import com.cowork.project.service.ProjectAccessChecker
import com.cowork.project.service.ProjectMemberService
import com.cowork.team.service.TeamAccessChecker
import com.cowork.user.service.UserService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.HttpStatus

class ProjectMemberServiceTest {

    private val memberRepository = mockk<ProjectMemberRepository>(relaxed = true)
    private val accessChecker = mockk<ProjectAccessChecker>()
    private val teamAccessChecker = mockk<TeamAccessChecker>()
    private val service = ProjectMemberService(
        memberRepository,
        accessChecker,
        teamAccessChecker,
        mockk<UserService>(relaxed = true),
        mockk<ApplicationEventPublisher>(relaxed = true),
    )

    private val project = Project(teamId = 1, name = "cowork", description = null, githubRepoUrl = null, position = 0, createdBy = 1, id = 3)

    @Test
    fun `마지막 소유자는 프로젝트에서 나갈 수 없다`() {
        every { accessChecker.requireReadable(3, 1) } returns project
        every { memberRepository.findByProjectIdAndUserId(3, 1) } returns ProjectMember(3, 1, ProjectMemberRole.OWNER)
        every { memberRepository.countByProjectIdAndRole(3, ProjectMemberRole.OWNER) } returns 1

        val exception = assertThrows<BusinessException> { service.removeMember(3, 1, 1) }

        assertEquals(HttpStatus.BAD_REQUEST, exception.status)
        verify(exactly = 0) { memberRepository.delete(any()) }
    }

    @Test
    fun `팀 멤버가 아닌 사용자는 프로젝트에 추가할 수 없다`() {
        every { accessChecker.requireManageable(3, 1) } returns project
        every { teamAccessChecker.isMember(1, 8) } returns false

        val exception = assertThrows<BusinessException> { service.addMember(3, 1, 8, ProjectMemberRole.EDITOR) }

        assertEquals(HttpStatus.BAD_REQUEST, exception.status)
    }
}
