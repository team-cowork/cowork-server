package com.cowork.team.controller

import com.cowork.team.dto.ChangeRoleRequest
import com.cowork.team.dto.InviteMembersRequest
import com.cowork.team.dto.TeamMemberResponse
import com.cowork.team.dto.TeamMembershipResponse
import com.cowork.team.service.TeamMemberService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@Tag(name = "팀 멤버", description = "팀 멤버 초대/조회/역할 변경/추방 API")
@RestController
@RequestMapping("/teams/{teamId}/members")
class TeamMemberController(
    private val teamMemberService: TeamMemberService,
) {

    @Operation(summary = "멤버 초대", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "초대 성공"),
        ApiResponse(responseCode = "403", description = "권한 없음"),
        ApiResponse(responseCode = "404", description = "팀 없음"),
    )
    @PostMapping
    fun inviteMembers(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable teamId: Long,
        @RequestBody request: InviteMembersRequest,
    ): ResponseEntity<List<TeamMemberResponse>> =
        ResponseEntity.status(HttpStatus.CREATED)
            .body(teamMemberService.inviteMembers(userId, teamId, request))

    @Operation(summary = "멤버 목록 조회", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @GetMapping
    fun getMembers(
        @PathVariable teamId: Long,
    ): ResponseEntity<List<TeamMemberResponse>> =
        ResponseEntity.ok(teamMemberService.getMembers(teamId))

    @Operation(summary = "멤버 여부 확인", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "멤버 여부 반환"),
    )
    @GetMapping("/{userId}/exists")
    fun isMember(
        @PathVariable teamId: Long,
        @PathVariable userId: Long,
    ): ResponseEntity<Map<String, Boolean>> =
        ResponseEntity.ok(mapOf("isMember" to teamMemberService.isMember(teamId, userId)))

    @Operation(summary = "단일 멤버 조회", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
        ApiResponse(responseCode = "404", description = "팀 멤버 아님 또는 팀 없음"),
    )
    @GetMapping("/{userId}")
    fun getMembership(
        @PathVariable teamId: Long,
        @PathVariable userId: Long,
    ): ResponseEntity<TeamMembershipResponse> =
        ResponseEntity.ok(teamMemberService.getMembership(teamId, userId))

    @Operation(summary = "멤버 역할 변경", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "변경 성공"),
        ApiResponse(responseCode = "403", description = "권한 없음"),
        ApiResponse(responseCode = "404", description = "멤버 없음"),
    )
    @PatchMapping("/{targetUserId}/role")
    fun changeRole(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable teamId: Long,
        @PathVariable targetUserId: Long,
        @RequestBody request: ChangeRoleRequest,
    ): ResponseEntity<Void> {
        teamMemberService.changeRole(userId, teamId, targetUserId, request)
        return ResponseEntity.ok().build()
    }

    @Operation(summary = "멤버 추방 / 탈퇴", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "추방/탈퇴 성공"),
        ApiResponse(responseCode = "403", description = "권한 없음 또는 OWNER 탈퇴 시도"),
        ApiResponse(responseCode = "404", description = "멤버 없음"),
    )
    @DeleteMapping("/{targetUserId}")
    fun removeMember(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable teamId: Long,
        @PathVariable targetUserId: Long,
    ): ResponseEntity<Void> {
        teamMemberService.removeMember(userId, teamId, targetUserId)
        return ResponseEntity.noContent().build()
    }
}
