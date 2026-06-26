package com.cowork.project.domain.project.presentation.controller

import com.cowork.project.domain.project.service.ProjectService

import com.cowork.project.domain.project.presentation.data.response.ProjectResDto
import com.cowork.project.domain.project.presentation.data.request.ReorderProjectsReqDto
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "팀 프로젝트", description = "팀 프로젝트 순서 API")
@RestController
@RequestMapping("/teams")
class TeamProjectController(
    private val projectService: ProjectService,
) {

    @Operation(summary = "팀 프로젝트 순서 변경", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "변경 성공"),
        ApiResponse(responseCode = "400", description = "유효하지 않은 프로젝트 순서"),
        ApiResponse(responseCode = "403", description = "해당 팀의 멤버가 아님"),
    )
    @PatchMapping("/{teamId}/projects/reorder")
    fun reorderTeamProjects(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable teamId: Long,
        @RequestBody request: ReorderProjectsReqDto,
    ): List<ProjectResDto> =
        projectService.reorderTeamProjects(userId, teamId, request.orderedProjectIds)
}
