package com.cowork.team.domain.teamRole.presentation.controller

import com.cowork.team.domain.teamRole.presentation.data.response.TeamRolePermissionGroupResponse
import com.cowork.team.domain.teamRole.service.QueryTeamRolePermissionsService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import team.themoment.sdk.response.CommonApiResponse

@Tag(name = "팀 역할")
@ApiResponses(
    ApiResponse(
        responseCode = "429",
        description = "요청 한도 초과",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = CommonApiResponse::class))],
    ),
    ApiResponse(
        responseCode = "401",
        description = "액세스 토큰이 없거나 유효하지 않음",
        content = [
            Content(
                mediaType = "application/json",
                schema = Schema(implementation = CommonApiResponse::class),
                examples = [
                    ExampleObject(
                        value = """{"status":"401 UNAUTHORIZED","code":401,"message":"Unauthorized"}""",
                    ),
                ],
            ),
        ],
    ),
    ApiResponse(
        responseCode = "default",
        description = "기타 오류",
        content = [Content(schema = Schema(implementation = CommonApiResponse::class))],
    ),
)
@RestController
@RequestMapping("/teams/role-permissions")
class TeamRolePermissionController(private val queryTeamRolePermissionsService: QueryTeamRolePermissionsService) {

    @Operation(
        summary = "역할 권한 목록 조회",
        description = "권한을 그룹별로 묶어 표시 순서대로 반환합니다. 각 권한에는 제목과 설명이 있습니다.",
        security = [SecurityRequirement(name = "BearerAuth")],
    )
    @ApiResponses(ApiResponse(responseCode = "200", description = "조회 성공"))
    @GetMapping
    fun getPermissions(): List<TeamRolePermissionGroupResponse> = queryTeamRolePermissionsService.execute()
}
