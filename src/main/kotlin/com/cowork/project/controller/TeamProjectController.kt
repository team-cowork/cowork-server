package com.cowork.project.controller

import com.cowork.global.security.AuthUser
import com.cowork.global.security.CurrentUser
import com.cowork.project.dto.ProjectResponse
import com.cowork.project.dto.ReorderProjectsRequest
import com.cowork.project.service.ProjectService
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/teams/{teamId}/projects")
class TeamProjectController(
    private val projectService: ProjectService,
) {

    @PatchMapping("/reorder")
    fun reorder(
        @CurrentUser user: AuthUser,
        @PathVariable teamId: Long,
        @RequestBody request: ReorderProjectsRequest,
    ): List<ProjectResponse> = projectService.reorder(teamId, user.id, request.projectIds)
}
