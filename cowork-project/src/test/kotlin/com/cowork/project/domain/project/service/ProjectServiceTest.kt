package com.cowork.project.domain.project.service


import com.cowork.project.domain.project.entity.Project
import com.cowork.project.domain.projectMember.entity.ProjectMember
import com.cowork.project.domain.projectMember.entity.ProjectMemberRole
import com.cowork.project.domain.membership.entity.TeamMembership
import com.cowork.project.domain.projectMember.presentation.data.request.AddProjectMemberReqDto
import com.cowork.project.domain.project.presentation.data.request.CreateProjectReqDto
import com.cowork.project.domain.github.presentation.data.request.LinkGithubRepoReqDto
import com.cowork.project.domain.project.presentation.data.request.UpdateProjectReqDto
import com.cowork.project.domain.project.event.ProjectEventPublisher
import com.cowork.project.domain.projectMember.repository.ProjectMemberRepository
import com.cowork.project.domain.project.repository.ProjectRepository
import com.cowork.project.domain.membership.repository.TeamMembershipRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.transaction.support.TransactionSynchronizationManager
import team.themoment.sdk.exception.ExpectedException
import java.util.Optional

class ProjectServiceTest {

    private val projectRepository = mockk<ProjectRepository>(relaxed = true)
    private val projectMemberRepository = mockk<ProjectMemberRepository>(relaxed = true)
    private val teamMembershipRepository = mockk<TeamMembershipRepository>()
    private val projectEventPublisher = mockk<ProjectEventPublisher>(relaxed = true)
    private val projectAccessGuard = ProjectAccessGuard(projectRepository, projectMemberRepository, teamMembershipRepository)

    private val service = ProjectService(projectRepository, projectMemberRepository, projectEventPublisher, projectAccessGuard)

    @BeforeEach
    fun setUp() {
        TransactionSynchronizationManager.initSynchronization()
    }

    @AfterEach
    fun tearDown() {
        TransactionSynchronizationManager.clear()
    }

    private fun project(id: Long = 1L, teamId: Long = 100L, position: Int = 0) =
        Project(id = id, teamId = teamId, name = "p", description = null, position = position, createdBy = 1L)

    private fun membership(teamId: Long, userId: Long, role: String = "MEMBER") =
        TeamMembership(teamId = teamId, userId = userId, role = role)

    @Test
    fun `createProject은 팀 멤버 아니면 FORBIDDEN`() {
        every { teamMembershipRepository.findByTeamIdAndUserId(100L, 7L) } returns null

        val ex = assertThrows(ExpectedException::class.java) {
            service.createProject(7L, CreateProjectReqDto(teamId = 100L, name = "p", description = null))
        }
        assertEquals(HttpStatus.FORBIDDEN, ex.statusCode)
        verify(exactly = 0) { projectRepository.save(any()) }
    }

    @Test
    fun `createProject은 마지막 position 다음 값으로 저장`() {
        every { teamMembershipRepository.findByTeamIdAndUserId(100L, 7L) } returns membership(100L, 7L)
        every { projectRepository.findMaxPositionByTeamId(100L) } returns 3
        every { projectRepository.save(any()) } answers { firstArg() }
        every { projectMemberRepository.save(any()) } answers { firstArg() }

        val response = service.createProject(7L, CreateProjectReqDto(teamId = 100L, name = "p", description = null))

        assertEquals(4, response.position)
    }

    @Test
    fun `reorderTeamProjects는 요청 순서대로 position을 갱신함`() {
        val first = project(id = 1L, position = 0)
        val second = project(id = 2L, position = 1)
        every { teamMembershipRepository.findByTeamIdAndUserId(100L, 7L) } returns membership(100L, 7L)
        every { projectRepository.findAllByTeamIdOrderByPositionAscIdAsc(100L) } returns listOf(first, second)

        val result = service.reorderTeamProjects(7L, 100L, listOf(2L, 1L))

        assertEquals(listOf(2L, 1L), result.map { it.id })
        assertEquals(1, first.position)
        assertEquals(0, second.position)
    }

    @Test
    fun `reorderTeamProjects는 팀 프로젝트 ID 누락 시 BAD_REQUEST`() {
        every { teamMembershipRepository.findByTeamIdAndUserId(100L, 7L) } returns membership(100L, 7L)
        every { projectRepository.findAllByTeamIdOrderByPositionAscIdAsc(100L) } returns listOf(project(id = 1L), project(id = 2L))

        val ex = assertThrows(ExpectedException::class.java) {
            service.reorderTeamProjects(7L, 100L, listOf(1L))
        }
        assertEquals(HttpStatus.BAD_REQUEST, ex.statusCode)
    }

    @Test
    fun `updateProject은 팀 OWNER 등가 권한으로 통과`() {
        val proj = project()
        every { projectRepository.findById(1L) } returns Optional.of(proj)
        every { projectMemberRepository.findByProjectIdAndUserId(1L, 99L) } returns null
        every { teamMembershipRepository.findByTeamIdAndUserId(100L, 99L) } returns membership(100L, 99L, "OWNER")

        val response = service.updateProject(99L, 1L, UpdateProjectReqDto(name = "newName"))
        assertEquals("newName", response.name)
    }

    @Test
    fun `updateProject은 팀 비멤버이면 FORBIDDEN`() {
        val proj = project()
        every { projectRepository.findById(1L) } returns Optional.of(proj)
        every { projectMemberRepository.findByProjectIdAndUserId(1L, 99L) } returns null
        every { teamMembershipRepository.findByTeamIdAndUserId(100L, 99L) } returns null

        val ex = assertThrows(ExpectedException::class.java) {
            service.updateProject(99L, 1L, UpdateProjectReqDto(name = "x"))
        }
        assertEquals(HttpStatus.FORBIDDEN, ex.statusCode)
    }

    @Test
    fun `addMember는 추가 대상이 팀 멤버 아니면 BAD_REQUEST`() {
        val proj = project()
        every { projectRepository.findById(1L) } returns Optional.of(proj)
        every { projectMemberRepository.findByProjectIdAndUserId(1L, 1L) } returns
            ProjectMember(projectId = 1L, userId = 1L, role = ProjectMemberRole.OWNER)
        every { teamMembershipRepository.findByTeamIdAndUserId(100L, 50L) } returns null

        val ex = assertThrows(ExpectedException::class.java) {
            service.addMember(1L, 1L, AddProjectMemberReqDto(userId = 50L, role = "EDITOR"))
        }
        assertEquals(HttpStatus.BAD_REQUEST, ex.statusCode)
    }

    @Test
    fun `linkGithubRepo는 유효한 URL이면 저장`() {
        val proj = project()
        every { projectRepository.findById(1L) } returns Optional.of(proj)
        every { projectMemberRepository.findByProjectIdAndUserId(1L, 99L) } returns
            ProjectMember(projectId = 1L, userId = 99L, role = ProjectMemberRole.OWNER)

        val response = service.linkGithubRepo(99L, 1L, LinkGithubRepoReqDto("https://github.com/my-org/my-repo"))

        assertEquals("https://github.com/my-org/my-repo", response.githubRepoUrl)
    }

    @Test
    fun `linkGithubRepo는 github_com이 아닌 호스트면 BAD_REQUEST`() {
        val proj = project()
        every { projectRepository.findById(1L) } returns Optional.of(proj)
        every { projectMemberRepository.findByProjectIdAndUserId(1L, 99L) } returns
            ProjectMember(projectId = 1L, userId = 99L, role = ProjectMemberRole.OWNER)

        val ex = assertThrows(ExpectedException::class.java) {
            service.linkGithubRepo(99L, 1L, LinkGithubRepoReqDto("https://gitlab.com/my-org/my-repo"))
        }
        assertEquals(HttpStatus.BAD_REQUEST, ex.statusCode)
    }

    @Test
    fun `linkGithubRepo는 EDITOR가 아니면 FORBIDDEN`() {
        val proj = project()
        every { projectRepository.findById(1L) } returns Optional.of(proj)
        every { projectMemberRepository.findByProjectIdAndUserId(1L, 50L) } returns
            ProjectMember(projectId = 1L, userId = 50L, role = ProjectMemberRole.VIEWER)
        every { teamMembershipRepository.findByTeamIdAndUserId(100L, 50L) } returns null

        val ex = assertThrows(ExpectedException::class.java) {
            service.linkGithubRepo(50L, 1L, LinkGithubRepoReqDto("https://github.com/my-org/my-repo"))
        }
        assertEquals(HttpStatus.FORBIDDEN, ex.statusCode)
    }

    @Test
    fun `unlinkGithubRepo는 githubRepoUrl을 null로 초기화`() {
        val proj = project().apply { linkGithubRepo("https://github.com/my-org/my-repo") }
        every { projectRepository.findById(1L) } returns Optional.of(proj)
        every { projectMemberRepository.findByProjectIdAndUserId(1L, 99L) } returns
            ProjectMember(projectId = 1L, userId = 99L, role = ProjectMemberRole.OWNER)

        val response = service.unlinkGithubRepo(99L, 1L)

        assertEquals(null, response.githubRepoUrl)
    }

    @Test
    fun `unlinkGithubRepo는 EDITOR가 아니면 FORBIDDEN`() {
        val proj = project().apply { linkGithubRepo("https://github.com/my-org/my-repo") }
        every { projectRepository.findById(1L) } returns Optional.of(proj)
        every { projectMemberRepository.findByProjectIdAndUserId(1L, 50L) } returns
            ProjectMember(projectId = 1L, userId = 50L, role = ProjectMemberRole.VIEWER)
        every { teamMembershipRepository.findByTeamIdAndUserId(100L, 50L) } returns null

        val ex = assertThrows(ExpectedException::class.java) {
            service.unlinkGithubRepo(50L, 1L)
        }
        assertEquals(HttpStatus.FORBIDDEN, ex.statusCode)
    }
}
