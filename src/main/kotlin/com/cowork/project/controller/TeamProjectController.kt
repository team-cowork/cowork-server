package com.cowork.project.controller

import com.cowork.global.security.AuthUser
import com.cowork.global.security.CurrentUser
import com.cowork.project.dto.ProjectResponse
import com.cowork.project.dto.ReorderProjectsRequest
import com.cowork.project.service.ProjectService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "프로젝트")
@RestController
@RequestMapping("/teams/{teamId}/projects")
class TeamProjectController(
    private val projectService: ProjectService,
) {

    @Operation(summary = "팀 프로젝트 순서 변경")
    @PatchMapping("/reorder")
    fun reorder(
        @CurrentUser user: AuthUser,
        @PathVariable teamId: Long,
        @RequestBody request: ReorderProjectsRequest,
    ): List<ProjectResponse> = projectService.reorder(teamId, user.id, request.projectIds)
}
