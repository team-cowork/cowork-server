package com.cowork.team.client

import com.cowork.team.dto.CreateTeamRoleRequest
import com.cowork.team.dto.TeamMemberRoleAssignmentResponse
import com.cowork.team.dto.TeamRoleResponse
import com.cowork.team.dto.UpdateTeamRoleRequest
import org.springframework.cloud.openfeign.FeignClient
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody

@FeignClient(name = "cowork-preference")
interface PreferenceTeamRoleClient {

    @GetMapping("/preferences/team/{teamId}/roles")
    fun getRoles(@PathVariable teamId: Long): List<TeamRoleResponse>

    @GetMapping("/preferences/team/{teamId}/roles/members/{accountId}")
    fun getMemberRoles(@PathVariable teamId: Long, @PathVariable accountId: Long): List<TeamRoleResponse>

    @GetMapping("/preferences/team/{teamId}/roles/members")
    fun getMemberRoleAssignments(@PathVariable teamId: Long): List<TeamMemberRoleAssignmentResponse>

    @PostMapping("/preferences/team/{teamId}/roles")
    fun createRole(@PathVariable teamId: Long, @RequestBody request: CreateTeamRoleRequest): TeamRoleResponse

    @PatchMapping("/preferences/team/{teamId}/roles/{roleId}")
    fun updateRole(
        @PathVariable teamId: Long,
        @PathVariable roleId: Long,
        @RequestBody request: UpdateTeamRoleRequest,
    ): TeamRoleResponse

    @DeleteMapping("/preferences/team/{teamId}/roles/{roleId}")
    fun deleteRole(@PathVariable teamId: Long, @PathVariable roleId: Long)

    @PostMapping("/preferences/team/{teamId}/roles/{roleId}/members")
    fun assignRole(
        @PathVariable teamId: Long,
        @PathVariable roleId: Long,
        @RequestBody body: Map<String, Long>,
    ): TeamRoleResponse

    @DeleteMapping("/preferences/team/{teamId}/roles/{roleId}/members/{accountId}")
    fun revokeRole(
        @PathVariable teamId: Long,
        @PathVariable accountId: Long,
        @PathVariable roleId: Long,
    )

    @DeleteMapping("/preferences/team/{teamId}/roles/members/{accountId}")
    fun deleteMemberRoles(@PathVariable teamId: Long, @PathVariable accountId: Long)
}
