package com.cowork.team.controller

import com.cowork.global.security.AuthUser
import com.cowork.global.security.CurrentUser
import com.cowork.team.dto.AddTeamMemberRequest
import com.cowork.team.dto.ChangeTeamMemberRoleRequest
import com.cowork.team.dto.TeamMemberExistsResponse
import com.cowork.team.dto.TeamMemberResponse
import com.cowork.team.service.TeamMemberService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "팀 멤버")
@RestController
@RequestMapping("/teams/{teamId}/members")
class TeamMemberController(
    private val teamMemberService: TeamMemberService,
) {

    @Operation(summary = "팀 멤버 초대")
    @PostMapping
    fun addMember(
        @CurrentUser user: AuthUser,
        @PathVariable teamId: Long,
        @Valid @RequestBody request: AddTeamMemberRequest,
    ): TeamMemberResponse = teamMemberService.addMember(teamId, user.id, request.userId)

    @Operation(summary = "팀 멤버 목록 조회")
    @GetMapping
    fun getMembers(@CurrentUser user: AuthUser, @PathVariable teamId: Long): List<TeamMemberResponse> =
        teamMemberService.getMembers(teamId, user.id)

    @Operation(summary = "팀 멤버 여부 확인")
    @GetMapping("/{userId}/exists")
    fun exists(@PathVariable teamId: Long, @PathVariable userId: Long): TeamMemberExistsResponse =
        TeamMemberExistsResponse(teamMemberService.isMember(teamId, userId))

    @Operation(summary = "팀 멤버 역할 변경")
    @PatchMapping("/{targetUserId}/role")
    fun changeRole(
        @CurrentUser user: AuthUser,
        @PathVariable teamId: Long,
        @PathVariable targetUserId: Long,
        @Valid @RequestBody request: ChangeTeamMemberRoleRequest,
    ): TeamMemberResponse = teamMemberService.changeRole(teamId, user.id, targetUserId, request.role)

    @Operation(summary = "팀 멤버 내보내기 또는 팀 나가기")
    @DeleteMapping("/{targetUserId}")
    fun removeMember(
        @CurrentUser user: AuthUser,
        @PathVariable teamId: Long,
        @PathVariable targetUserId: Long,
    ) = teamMemberService.removeMember(teamId, user.id, targetUserId)
}
