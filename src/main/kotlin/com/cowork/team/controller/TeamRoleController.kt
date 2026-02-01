package com.cowork.team.controller

import com.cowork.global.security.AuthUser
import com.cowork.global.security.CurrentUser
import com.cowork.team.dto.CreateTeamRoleRequest
import com.cowork.team.dto.TeamRoleResponse
import com.cowork.team.dto.UpdateTeamRoleRequest
import com.cowork.team.service.TeamRoleService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "팀 역할")
@RestController
@RequestMapping("/teams/{teamId}")
class TeamRoleController(
    private val teamRoleService: TeamRoleService,
) {

    @Operation(summary = "팀 역할 목록 조회")
    @GetMapping("/roles")
    fun getRoles(@CurrentUser user: AuthUser, @PathVariable teamId: Long): List<TeamRoleResponse> =
        teamRoleService.getRoles(teamId, user.id)

    @Operation(summary = "멤버에게 부여된 역할 조회")
    @GetMapping("/members/{userId}/roles")
    fun getMemberRoles(
        @CurrentUser user: AuthUser,
        @PathVariable teamId: Long,
        @PathVariable userId: Long,
    ): List<TeamRoleResponse> = teamRoleService.getMemberRoles(teamId, user.id, userId)

    @Operation(summary = "팀 역할 생성")
    @PostMapping("/roles")
    fun create(
        @CurrentUser user: AuthUser,
        @PathVariable teamId: Long,
        @RequestBody request: CreateTeamRoleRequest,
    ): TeamRoleResponse = teamRoleService.create(teamId, user.id, request)

    @Operation(summary = "팀 역할 수정")
    @PatchMapping("/roles/{roleId}")
    fun update(
        @CurrentUser user: AuthUser,
        @PathVariable teamId: Long,
        @PathVariable roleId: Long,
        @RequestBody request: UpdateTeamRoleRequest,
    ): TeamRoleResponse = teamRoleService.update(teamId, user.id, roleId, request)

    @Operation(summary = "팀 역할 삭제")
    @DeleteMapping("/roles/{roleId}")
    fun delete(
        @CurrentUser user: AuthUser,
        @PathVariable teamId: Long,
        @PathVariable roleId: Long,
    ) = teamRoleService.delete(teamId, user.id, roleId)

    @Operation(summary = "멤버에게 역할 부여")
    @PutMapping("/members/{targetUserId}/roles/{roleId}")
    fun assign(
        @CurrentUser user: AuthUser,
        @PathVariable teamId: Long,
        @PathVariable targetUserId: Long,
        @PathVariable roleId: Long,
    ) = teamRoleService.assign(teamId, user.id, targetUserId, roleId)

    @Operation(summary = "멤버의 역할 회수")
    @DeleteMapping("/members/{targetUserId}/roles/{roleId}")
    fun unassign(
        @CurrentUser user: AuthUser,
        @PathVariable teamId: Long,
        @PathVariable targetUserId: Long,
        @PathVariable roleId: Long,
    ) = teamRoleService.unassign(teamId, user.id, targetUserId, roleId)
}
