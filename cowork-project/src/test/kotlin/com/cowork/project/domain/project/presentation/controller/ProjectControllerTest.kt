package com.cowork.project.domain.project.presentation.controller

import com.cowork.project.domain.project.entity.ProjectStatus
import com.cowork.project.domain.project.presentation.data.request.CreateProjectReqDto
import com.cowork.project.domain.project.presentation.data.request.UpdateProjectReqDto
import com.cowork.project.domain.project.presentation.data.response.ProjectDetailResDto
import com.cowork.project.domain.project.presentation.data.response.ProjectResDto
import com.cowork.project.domain.project.service.AddProjectMemberService
import com.cowork.project.domain.project.service.CreateProjectService
import com.cowork.project.domain.project.service.DeleteProjectService
import com.cowork.project.domain.project.service.QueryMyProjectsService
import com.cowork.project.domain.project.service.QueryProjectMembersService
import com.cowork.project.domain.project.service.QueryProjectService
import com.cowork.project.domain.project.service.QueryProjectsByTeamIdService
import com.cowork.project.domain.project.service.RemoveProjectMemberService
import com.cowork.project.domain.project.service.UpdateProjectMemberRoleService
import com.cowork.project.domain.project.service.UpdateProjectService
import com.cowork.project.domain.projectMember.entity.ProjectMemberRole
import com.cowork.project.domain.projectMember.presentation.data.request.AddProjectMemberReqDto
import com.cowork.project.domain.projectMember.presentation.data.request.UpdateProjectMemberRoleReqDto
import com.cowork.project.domain.projectMember.presentation.data.response.ProjectMemberResDto
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.http.HttpStatus
import java.time.LocalDateTime

/**
 * Gateway가 전달하는 X-User-Id 헤더가 컨트롤러 메서드 파라미터로 정확히 바인딩되어
 * 서비스로 그대로 전달되는지 검증하는 순수 단위 테스트. 스프링 컨텍스트를 기동하지 않고
 * 컨트롤러를 직접 생성하여 헤더 인자를 호출부에서 흉내 낸다.
 */
class ProjectControllerTest : DescribeSpec({

    val createProjectService = mockk<CreateProjectService>()
    val queryProjectService = mockk<QueryProjectService>()
    val updateProjectService = mockk<UpdateProjectService>()
    val deleteProjectService = mockk<DeleteProjectService>(relaxed = true)
    val queryProjectsByTeamIdService = mockk<QueryProjectsByTeamIdService>()
    val queryMyProjectsService = mockk<QueryMyProjectsService>()
    val addProjectMemberService = mockk<AddProjectMemberService>()
    val queryProjectMembersService = mockk<QueryProjectMembersService>()
    val updateProjectMemberRoleService = mockk<UpdateProjectMemberRoleService>()
    val removeProjectMemberService = mockk<RemoveProjectMemberService>(relaxed = true)

    val controller = ProjectController(
        createProjectService,
        queryProjectService,
        updateProjectService,
        deleteProjectService,
        queryProjectsByTeamIdService,
        queryMyProjectsService,
        addProjectMemberService,
        queryProjectMembersService,
        updateProjectMemberRoleService,
        removeProjectMemberService,
    )

    fun sampleProject(id: Long = 1L) = ProjectResDto(
        id = id,
        teamId = 100L,
        name = "project",
        description = null,
        status = ProjectStatus.ACTIVE,
        position = 0,
        createdBy = 42L,
        createdAt = LocalDateTime.now(),
        updatedAt = LocalDateTime.now(),
    )

    fun sampleProjectDetail(id: Long = 1L) = ProjectDetailResDto(
        id = id,
        teamId = 100L,
        name = "project",
        description = null,
        status = ProjectStatus.ACTIVE,
        position = 0,
        createdBy = 42L,
        createdAt = LocalDateTime.now(),
        updatedAt = LocalDateTime.now(),
        memberCount = 3L,
    )

    fun sampleMember(id: Long = 1L, userId: Long = 42L) = ProjectMemberResDto(
        id = id,
        projectId = 1L,
        userId = userId,
        role = ProjectMemberRole.EDITOR,
        joinedAt = LocalDateTime.now(),
    )

    describe("ProjectController 클래스의") {

        describe("createProject 메서드는") {
            it("X-User-Id 헤더 값을 그대로 서비스로 전달한다") {
                // Given
                val userId = 42L
                val request = CreateProjectReqDto(teamId = 100L, name = "p", description = null)
                every { createProjectService.execute(userId, request) } returns sampleProject()

                // When
                val response = controller.createProject(userId, request)

                // Then
                response.statusCode shouldBe HttpStatus.CREATED
                verify(exactly = 1) { createProjectService.execute(userId, request) }
            }
        }

        describe("getProject 메서드는") {
            it("X-User-Id 헤더 값을 그대로 서비스로 전달한다") {
                // Given
                val userId = 7L
                val projectId = 99L
                every { queryProjectService.execute(userId, projectId) } returns sampleProjectDetail(projectId)

                // When
                val response = controller.getProject(userId, projectId)

                // Then
                response.statusCode shouldBe HttpStatus.OK
                verify(exactly = 1) { queryProjectService.execute(userId, projectId) }
            }
        }

        describe("updateProject 메서드는") {
            it("X-User-Id 헤더 값을 그대로 서비스로 전달한다") {
                // Given
                val userId = 11L
                val projectId = 21L
                val request = UpdateProjectReqDto(name = "new name")
                every { updateProjectService.execute(userId, projectId, request) } returns sampleProject(projectId)

                // When
                val response = controller.updateProject(userId, projectId, request)

                // Then
                response.statusCode shouldBe HttpStatus.OK
                verify(exactly = 1) { updateProjectService.execute(userId, projectId, request) }
            }
        }

        describe("deleteProject 메서드는") {
            it("X-User-Id 헤더 값을 그대로 서비스로 전달한다") {
                // Given
                val userId = 13L
                val projectId = 31L

                // When
                val response = controller.deleteProject(userId, projectId)

                // Then
                response.statusCode shouldBe HttpStatus.NO_CONTENT
                verify(exactly = 1) { deleteProjectService.execute(userId, projectId) }
            }
        }

        describe("getProjectsByTeamId 메서드는") {
            it("X-User-Id 헤더 값을 그대로 서비스로 전달한다") {
                // Given
                val userId = 5L
                val teamId = 100L
                val pageable = PageRequest.of(0, 20)
                val page = PageImpl(listOf(sampleProject()))
                every { queryProjectsByTeamIdService.execute(userId, teamId, pageable) } returns page

                // When
                val response = controller.getProjectsByTeamId(userId, teamId, pageable)

                // Then
                response.statusCode shouldBe HttpStatus.OK
                verify(exactly = 1) { queryProjectsByTeamIdService.execute(userId, teamId, pageable) }
            }
        }

        describe("getMyProjects 메서드는") {
            it("X-User-Id 헤더 값을 그대로 서비스로 전달한다") {
                // Given
                val userId = 6L
                val pageable = PageRequest.of(0, 20)
                val page = PageImpl(listOf(sampleProject()))
                every { queryMyProjectsService.execute(userId, pageable) } returns page

                // When
                val response = controller.getMyProjects(userId, pageable)

                // Then
                response.statusCode shouldBe HttpStatus.OK
                verify(exactly = 1) { queryMyProjectsService.execute(userId, pageable) }
            }
        }

        describe("addMember 메서드는") {
            it("X-User-Id 헤더 값을 그대로 서비스로 전달한다") {
                // Given
                val userId = 8L
                val projectId = 1L
                val request = AddProjectMemberReqDto(userId = 42L, role = ProjectMemberRole.EDITOR)
                every { addProjectMemberService.execute(userId, projectId, request) } returns sampleMember()

                // When
                val response = controller.addMember(userId, projectId, request)

                // Then
                response.statusCode shouldBe HttpStatus.CREATED
                verify(exactly = 1) { addProjectMemberService.execute(userId, projectId, request) }
            }
        }

        describe("getMembers 메서드는") {
            it("X-User-Id 헤더 값을 그대로 서비스로 전달한다") {
                // Given
                val userId = 9L
                val projectId = 1L
                every { queryProjectMembersService.execute(userId, projectId) } returns listOf(sampleMember())

                // When
                val response = controller.getMembers(userId, projectId)

                // Then
                response.statusCode shouldBe HttpStatus.OK
                verify(exactly = 1) { queryProjectMembersService.execute(userId, projectId) }
            }
        }

        describe("updateMemberRole 메서드는") {
            it("X-User-Id 헤더 값을 그대로 서비스로 전달한다") {
                // Given
                val userId = 10L
                val projectId = 1L
                val memberId = 2L
                val request = UpdateProjectMemberRoleReqDto(role = ProjectMemberRole.VIEWER)
                every {
                    updateProjectMemberRoleService.execute(userId, projectId, memberId, request)
                } returns sampleMember(memberId)

                // When
                val response = controller.updateMemberRole(userId, projectId, memberId, request)

                // Then
                response.statusCode shouldBe HttpStatus.OK
                verify(exactly = 1) { updateProjectMemberRoleService.execute(userId, projectId, memberId, request) }
            }
        }

        describe("removeMember 메서드는") {
            it("X-User-Id 헤더 값을 그대로 서비스로 전달한다") {
                // Given
                val userId = 15L
                val projectId = 1L
                val memberId = 2L

                // When
                val response = controller.removeMember(userId, projectId, memberId)

                // Then
                response.statusCode shouldBe HttpStatus.NO_CONTENT
                verify(exactly = 1) { removeProjectMemberService.execute(userId, projectId, memberId) }
            }
        }
    }
})
