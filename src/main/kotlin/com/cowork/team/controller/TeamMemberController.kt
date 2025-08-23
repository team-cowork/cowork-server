package com.cowork.team.controller

import com.cowork.global.security.AuthUser
import com.cowork.global.security.CurrentUser
import com.cowork.team.dto.AddTeamMemberRequest
import com.cowork.team.dto.ChangeTeamMemberRoleRequest
import com.cowork.team.dto.TeamMemberExistsResponse
import com.cowork.team.dto.TeamMemberResponse
import com.cowork.team.service.TeamMemberService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/teams/{teamId}/members")
class TeamMemberController(
    private val teamMemberService: TeamMemberService,
) {

    @PostMapping
    fun addMember(
        @CurrentUser user: AuthUser,
        @PathVariable teamId: Long,
        @RequestBody request: AddTeamMemberRequest,
    ): TeamMemberResponse = teamMemberService.addMember(teamId, user.id, request.userId)

    @GetMapping
    fun getMembers(@CurrentUser user: AuthUser, @PathVariable teamId: Long): List<TeamMemberResponse> =
        teamMemberService.getMembers(teamId, user.id)

    @GetMapping("/{userId}/exists")
    fun exists(@PathVariable teamId: Long, @PathVariable userId: Long): TeamMemberExistsResponse =
        TeamMemberExistsResponse(teamMemberService.isMember(teamId, userId))
}
