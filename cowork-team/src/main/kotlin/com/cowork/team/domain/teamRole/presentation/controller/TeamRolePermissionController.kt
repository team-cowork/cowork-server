package com.cowork.team.domain.teamRole.presentation.controller

import com.cowork.team.domain.teamRole.presentation.data.response.TeamRolePermissionGroupResponse
import com.cowork.team.domain.teamRole.service.QueryTeamRolePermissionsService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "팀 역할", description = "팀 커스텀 역할/권한/색상/우선순위/멘션 설정 API")
@RestController
@RequestMapping("/teams/role-permissions")
class TeamRolePermissionController(private val queryTeamRolePermissionsService: QueryTeamRolePermissionsService) {

    @Operation(
        summary = "역할 권한 목록 조회",
        description = "역할 설정 화면에 표시할 권한을 그룹별로 제목·설명과 함께 반환합니다. 그룹과 권한은 표시 순서대로 정렬되어 있습니다.",
        security = [SecurityRequirement(name = "BearerAuth")],
    )
    @ApiResponses(ApiResponse(responseCode = "200", description = "조회 성공"))
    @GetMapping
    fun getPermissions(): List<TeamRolePermissionGroupResponse> = queryTeamRolePermissionsService.execute()
}
