package com.cowork.project.domain.project.presentation.controller

import com.cowork.project.domain.project.presentation.data.request.ReorderProjectsReqDto
import com.cowork.project.domain.project.presentation.data.response.ProjectResDto
import com.cowork.project.domain.project.service.ReorderTeamProjectsService
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
class TeamProjectController(private val reorderTeamProjectsService: ReorderTeamProjectsService) {

    @Operation(
        summary = "팀 프로젝트 순서 변경",
        description = "조회 가능한 모든 프로젝트 ID를 전달합니다. 위치가 바뀌는 프로젝트마다 관리 또는 reorder 권한이 필요합니다. 숨겨진 프로젝트의 위치는 유지합니다.",
        security = [SecurityRequirement(name = "BearerAuth")],
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "변경 성공"),
        ApiResponse(responseCode = "400", description = "유효하지 않은 프로젝트 순서"),
        ApiResponse(responseCode = "403", description = "팀 멤버가 아니거나 재정렬 권한이 없음"),
    )
    @PatchMapping("/{teamId}/projects/reorder")
    fun reorderTeamProjects(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable teamId: Long,
        @RequestBody request: ReorderProjectsReqDto,
    ): List<ProjectResDto> = reorderTeamProjectsService.execute(userId, teamId, request.orderedProjectIds)
}
