package com.cowork.team.controller

import com.cowork.team.dto.CreateInviteRequest
import com.cowork.team.dto.InviteResponse
import com.cowork.team.dto.JoinTeamResponse
import com.cowork.team.service.TeamInviteService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@Tag(name = "팀 초대", description = "팀 초대 링크 생성·조회·삭제 및 코드로 팀 가입 API")
@RestController
@RequestMapping("/teams")
class TeamInviteController(
    private val teamInviteService: TeamInviteService,
) {

    @Operation(summary = "초대 링크 생성", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "생성 성공"),
        ApiResponse(responseCode = "403", description = "팀 멤버 아님"),
        ApiResponse(responseCode = "404", description = "팀 없음"),
    )
    @PostMapping("/{teamId}/invites")
    fun createInvite(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable teamId: Long,
        @RequestBody request: CreateInviteRequest,
    ): ResponseEntity<InviteResponse> =
        ResponseEntity.status(HttpStatus.CREATED)
            .body(teamInviteService.createInvite(userId, teamId, request))

    @Operation(summary = "초대 링크 목록 조회 (만료 포함)", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
        ApiResponse(responseCode = "403", description = "팀 멤버 아님"),
    )
    @GetMapping("/{teamId}/invites")
    fun getInvites(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable teamId: Long,
    ): ResponseEntity<List<InviteResponse>> =
        ResponseEntity.ok(teamInviteService.getInvites(userId, teamId))

    @Operation(summary = "초대 링크 무효화", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "삭제 성공"),
        ApiResponse(responseCode = "403", description = "권한 없음"),
        ApiResponse(responseCode = "404", description = "초대 링크 없음"),
    )
    @DeleteMapping("/{teamId}/invites/{inviteCode}")
    fun deleteInvite(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable teamId: Long,
        @PathVariable inviteCode: String,
    ): ResponseEntity<Void> {
        teamInviteService.deleteInvite(userId, teamId, inviteCode)
        return ResponseEntity.noContent().build()
    }

    @Operation(summary = "초대 코드로 팀 가입", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "가입 성공"),
        ApiResponse(responseCode = "404", description = "유효하지 않은 초대 코드"),
        ApiResponse(responseCode = "409", description = "이미 팀 멤버"),
        ApiResponse(responseCode = "410", description = "만료된 초대 링크"),
    )
    @PostMapping("/join/{inviteCode}")
    fun joinTeam(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable inviteCode: String,
    ): ResponseEntity<JoinTeamResponse> =
        ResponseEntity.ok(teamInviteService.joinTeam(userId, inviteCode))
}
