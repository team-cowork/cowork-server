package com.cowork.project.controller

import com.cowork.global.security.AuthUser
import com.cowork.global.security.CurrentUser
import com.cowork.project.dto.CreateProjectRequest
import com.cowork.project.dto.ProjectResponse
import com.cowork.project.dto.UpdateProjectRequest
import com.cowork.project.service.ProjectService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/projects")
class ProjectController(
    private val projectService: ProjectService,
) {

    @PostMapping
    fun create(
        @CurrentUser user: AuthUser,
        @RequestBody request: CreateProjectRequest,
    ): ProjectResponse = projectService.create(user.id, request)

    @GetMapping
    fun getTeamProjects(@CurrentUser user: AuthUser, @RequestParam teamId: Long): List<ProjectResponse> =
        projectService.getTeamProjects(teamId, user.id)

    @GetMapping("/me")
    fun getMyProjects(@CurrentUser user: AuthUser): List<ProjectResponse> = projectService.getMyProjects(user.id)

    @GetMapping("/{projectId}")
    fun getProject(@CurrentUser user: AuthUser, @PathVariable projectId: Long): ProjectResponse =
        projectService.getProject(projectId, user.id)
}
