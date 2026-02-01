package com.cowork.team.controller

import com.cowork.global.security.AuthUser
import com.cowork.global.security.CurrentUser
import com.cowork.team.dto.CreateTeamRoleRequest
import com.cowork.team.dto.TeamRoleResponse
import com.cowork.team.dto.UpdateTeamRoleRequest
import com.cowork.team.service.TeamRoleService
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

@RestController
@RequestMapping("/teams/{teamId}")
class TeamRoleController(
    private val teamRoleService: TeamRoleService,
) {

    @GetMapping("/roles")
    fun getRoles(@CurrentUser user: AuthUser, @PathVariable teamId: Long): List<TeamRoleResponse> =
        teamRoleService.getRoles(teamId, user.id)

    @GetMapping("/members/{userId}/roles")
    fun getMemberRoles(
        @CurrentUser user: AuthUser,
        @PathVariable teamId: Long,
        @PathVariable userId: Long,
    ): List<TeamRoleResponse> = teamRoleService.getMemberRoles(teamId, user.id, userId)

    @PostMapping("/roles")
    fun create(
        @CurrentUser user: AuthUser,
        @PathVariable teamId: Long,
        @RequestBody request: CreateTeamRoleRequest,
    ): TeamRoleResponse = teamRoleService.create(teamId, user.id, request)

    @PatchMapping("/roles/{roleId}")
    fun update(
        @CurrentUser user: AuthUser,
        @PathVariable teamId: Long,
        @PathVariable roleId: Long,
        @RequestBody request: UpdateTeamRoleRequest,
    ): TeamRoleResponse = teamRoleService.update(teamId, user.id, roleId, request)

    @DeleteMapping("/roles/{roleId}")
    fun delete(
        @CurrentUser user: AuthUser,
        @PathVariable teamId: Long,
        @PathVariable roleId: Long,
    ) = teamRoleService.delete(teamId, user.id, roleId)

    @PutMapping("/members/{targetUserId}/roles/{roleId}")
    fun assign(
        @CurrentUser user: AuthUser,
        @PathVariable teamId: Long,
        @PathVariable targetUserId: Long,
        @PathVariable roleId: Long,
    ) = teamRoleService.assign(teamId, user.id, targetUserId, roleId)
}
