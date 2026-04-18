package com.cowork.team.controller

import com.cowork.team.dto.ChangeRoleRequest
import com.cowork.team.dto.InviteMembersRequest
import com.cowork.team.dto.TeamMemberResponse
import com.cowork.team.service.TeamMemberService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/teams/{teamId}/members")
class TeamMemberController(
    private val teamMemberService: TeamMemberService,
) {

    @PostMapping
    fun inviteMembers(
        @RequestHeader("X-User-Id") userId: Long,
        @PathVariable teamId: Long,
        @RequestBody request: InviteMembersRequest,
    ): ResponseEntity<List<TeamMemberResponse>> =
        ResponseEntity.status(HttpStatus.CREATED)
            .body(teamMemberService.inviteMembers(userId, teamId, request))

    @GetMapping
    fun getMembers(
        @PathVariable teamId: Long,
    ): ResponseEntity<List<TeamMemberResponse>> =
        ResponseEntity.ok(teamMemberService.getMembers(teamId))

    @PatchMapping("/{targetUserId}/role")
    fun changeRole(
        @RequestHeader("X-User-Id") userId: Long,
        @PathVariable teamId: Long,
        @PathVariable targetUserId: Long,
        @RequestBody request: ChangeRoleRequest,
    ): ResponseEntity<Void> {
        teamMemberService.changeRole(userId, teamId, targetUserId, request)
        return ResponseEntity.ok().build()
    }

    @DeleteMapping("/{targetUserId}")
    fun removeMember(
        @RequestHeader("X-User-Id") userId: Long,
        @PathVariable teamId: Long,
        @PathVariable targetUserId: Long,
    ): ResponseEntity<Void> {
        teamMemberService.removeMember(userId, teamId, targetUserId)
        return ResponseEntity.noContent().build()
    }
}
