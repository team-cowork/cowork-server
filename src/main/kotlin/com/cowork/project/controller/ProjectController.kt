package com.cowork.project.controller

import com.cowork.global.security.AuthUser
import com.cowork.global.security.CurrentUser
import com.cowork.project.dto.AddProjectMemberRequest
import com.cowork.project.dto.ChangeProjectMemberRoleRequest
import com.cowork.project.dto.CreateProjectRequest
import com.cowork.project.dto.ProjectMemberResponse
import com.cowork.project.dto.ProjectResponse
import com.cowork.project.dto.UpdateProjectRequest
import com.cowork.project.service.ProjectMemberService
import com.cowork.project.service.ProjectService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@Tag(name = "프로젝트")
@RestController
@RequestMapping("/projects")
class ProjectController(
    private val projectService: ProjectService,
    private val projectMemberService: ProjectMemberService,
) {

    @Operation(summary = "프로젝트 생성")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @CurrentUser user: AuthUser,
        @Valid @RequestBody request: CreateProjectRequest,
    ): ProjectResponse = projectService.create(user.id, request)

    @Operation(summary = "팀 프로젝트 목록 조회")
    @GetMapping
    fun getTeamProjects(@CurrentUser user: AuthUser, @RequestParam teamId: Long): List<ProjectResponse> =
        projectService.getTeamProjects(teamId, user.id)

    @Operation(summary = "내가 속한 프로젝트 목록 조회")
    @GetMapping("/me")
    fun getMyProjects(@CurrentUser user: AuthUser): List<ProjectResponse> = projectService.getMyProjects(user.id)

    @Operation(summary = "프로젝트 조회")
    @GetMapping("/{projectId}")
    fun getProject(@CurrentUser user: AuthUser, @PathVariable projectId: Long): ProjectResponse =
        projectService.getProject(projectId, user.id)

    @Operation(summary = "프로젝트 수정")
    @PatchMapping("/{projectId}")
    fun update(
        @CurrentUser user: AuthUser,
        @PathVariable projectId: Long,
        @Valid @RequestBody request: UpdateProjectRequest,
    ): ProjectResponse = projectService.update(projectId, user.id, request)

    @Operation(summary = "프로젝트 삭제")
    @DeleteMapping("/{projectId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@CurrentUser user: AuthUser, @PathVariable projectId: Long) = projectService.delete(projectId, user.id)

    @Operation(summary = "프로젝트 멤버 추가")
    @PostMapping("/{projectId}/members")
    @ResponseStatus(HttpStatus.CREATED)
    fun addMember(
        @CurrentUser user: AuthUser,
        @PathVariable projectId: Long,
        @Valid @RequestBody request: AddProjectMemberRequest,
    ): ProjectMemberResponse = projectMemberService.addMember(projectId, user.id, request.userId, request.role)
}
