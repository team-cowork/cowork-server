package com.cowork.project.controller

import com.cowork.project.dto.*
import com.cowork.project.service.ProjectService
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.web.PageableDefault
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/projects")
class ProjectController(
    private val projectService: ProjectService,
) {

    @PostMapping
    fun createProject(
        @RequestHeader("X-User-Id") userId: Long,
        @RequestBody request: CreateProjectRequest,
    ): ResponseEntity<ProjectResponse> =
        ResponseEntity.status(201).body(projectService.createProject(userId, request))

    @GetMapping("/{projectId}")
    fun getProject(
        @PathVariable projectId: Long,
    ): ResponseEntity<ProjectDetailResponse> =
        ResponseEntity.ok(projectService.getProject(projectId))

    @PatchMapping("/{projectId}")
    fun updateProject(
        @RequestHeader("X-User-Id") userId: Long,
        @PathVariable projectId: Long,
        @RequestBody request: UpdateProjectRequest,
    ): ResponseEntity<ProjectResponse> =
        ResponseEntity.ok(projectService.updateProject(userId, projectId, request))

    @DeleteMapping("/{projectId}")
    fun deleteProject(
        @RequestHeader("X-User-Id") userId: Long,
        @PathVariable projectId: Long,
    ): ResponseEntity<Void> {
        projectService.deleteProject(userId, projectId)
        return ResponseEntity.noContent().build()
    }

    @GetMapping
    fun getProjectsByTeamId(
        @RequestParam teamId: Long,
        @PageableDefault(size = 20, sort = ["createdAt"]) pageable: Pageable,
    ): ResponseEntity<Page<ProjectResponse>> =
        ResponseEntity.ok(projectService.getProjectsByTeamId(teamId, pageable))

    @GetMapping("/me")
    fun getMyProjects(
        @RequestHeader("X-User-Id") userId: Long,
        @PageableDefault(size = 20, sort = ["createdAt"]) pageable: Pageable,
    ): ResponseEntity<Page<ProjectResponse>> =
        ResponseEntity.ok(projectService.getMyProjects(userId, pageable))

    @PostMapping("/{projectId}/members")
    fun addMember(
        @RequestHeader("X-User-Id") userId: Long,
        @PathVariable projectId: Long,
        @RequestBody request: AddProjectMemberRequest,
    ): ResponseEntity<ProjectMemberResponse> =
        ResponseEntity.status(201).body(projectService.addMember(userId, projectId, request))

    @GetMapping("/{projectId}/members")
    fun getMembers(
        @PathVariable projectId: Long,
    ): ResponseEntity<List<ProjectMemberResponse>> =
        ResponseEntity.ok(projectService.getMembers(projectId))

    @PatchMapping("/{projectId}/members/{memberId}")
    fun updateMemberRole(
        @RequestHeader("X-User-Id") userId: Long,
        @PathVariable projectId: Long,
        @PathVariable memberId: Long,
        @RequestBody request: UpdateProjectMemberRoleRequest,
    ): ResponseEntity<ProjectMemberResponse> =
        ResponseEntity.ok(projectService.updateMemberRole(userId, projectId, memberId, request))

    @DeleteMapping("/{projectId}/members/{memberId}")
    fun removeMember(
        @RequestHeader("X-User-Id") userId: Long,
        @PathVariable projectId: Long,
        @PathVariable memberId: Long,
    ): ResponseEntity<Void> {
        projectService.removeMember(userId, projectId, memberId)
        return ResponseEntity.noContent().build()
    }
}
