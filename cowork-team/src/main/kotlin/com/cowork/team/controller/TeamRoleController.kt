package com.cowork.team.controller

import com.cowork.team.dto.CreateTeamRoleRequest
import com.cowork.team.dto.TeamRoleResponse
import com.cowork.team.dto.UpdateTeamRoleRequest
import com.cowork.team.service.TeamRoleService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "팀 역할", description = "팀 커스텀 역할/권한/색상/우선순위/멘션 설정 API")
@RestController
@RequestMapping("/teams/{teamId}")
class TeamRoleController(
    private val teamRoleService: TeamRoleService,
) {

    @Operation(summary = "역할 목록 조회", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @GetMapping("/roles")
    fun getRoles(@PathVariable teamId: Long): ResponseEntity<List<TeamRoleResponse>> =
        ResponseEntity.ok(teamRoleService.getRoles(teamId))

    @Operation(summary = "멤버 역할 목록 조회", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @GetMapping("/members/{userId}/roles")
    fun getMemberRoles(
        @PathVariable teamId: Long,
        @PathVariable userId: Long,
    ): ResponseEntity<List<TeamRoleResponse>> =
        ResponseEntity.ok(teamRoleService.getMemberRoles(teamId, userId))

    @Operation(summary = "역할 생성", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "생성 성공"),
        ApiResponse(responseCode = "403", description = "권한 없음"),
        ApiResponse(responseCode = "409", description = "역할 이름 중복"),
    )
    @PostMapping("/roles")
    fun createRole(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable teamId: Long,
        @RequestBody request: CreateTeamRoleRequest,
    ): ResponseEntity<TeamRoleResponse> =
        ResponseEntity.status(HttpStatus.CREATED).body(teamRoleService.createRole(userId, teamId, request))

    @Operation(summary = "역할 수정", security = [SecurityRequirement(name = "BearerAuth")])
    @PatchMapping("/roles/{roleId}")
    fun updateRole(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable teamId: Long,
        @PathVariable roleId: Long,
        @RequestBody request: UpdateTeamRoleRequest,
    ): ResponseEntity<TeamRoleResponse> =
        ResponseEntity.ok(teamRoleService.updateRole(userId, teamId, roleId, request))

    @Operation(summary = "역할 삭제", security = [SecurityRequirement(name = "BearerAuth")])
    @DeleteMapping("/roles/{roleId}")
    fun deleteRole(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable teamId: Long,
        @PathVariable roleId: Long,
    ): ResponseEntity<Void> {
        teamRoleService.deleteRole(userId, teamId, roleId)
        return ResponseEntity.noContent().build()
    }

    @Operation(summary = "멤버에게 역할 부여", security = [SecurityRequirement(name = "BearerAuth")])
    @PutMapping("/members/{targetUserId}/roles/{roleId}")
    fun assignRole(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable teamId: Long,
        @PathVariable targetUserId: Long,
        @PathVariable roleId: Long,
    ): ResponseEntity<TeamRoleResponse> =
        ResponseEntity.ok(teamRoleService.assignRole(userId, teamId, targetUserId, roleId))

    @Operation(summary = "멤버 역할 회수", security = [SecurityRequirement(name = "BearerAuth")])
    @DeleteMapping("/members/{targetUserId}/roles/{roleId}")
    fun revokeRole(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable teamId: Long,
        @PathVariable targetUserId: Long,
        @PathVariable roleId: Long,
    ): ResponseEntity<Void> {
        teamRoleService.revokeRole(userId, teamId, targetUserId, roleId)
        return ResponseEntity.noContent().build()
    }
}
