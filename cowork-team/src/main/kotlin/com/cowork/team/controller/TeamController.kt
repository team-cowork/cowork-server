package com.cowork.team.controller

import com.cowork.team.dto.CreateTeamRequest
import com.cowork.team.dto.TeamResponse
import com.cowork.team.dto.TeamSummaryResponse
import com.cowork.team.dto.UpdateTeamRequest
import com.cowork.team.service.TeamService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@Tag(name = "팀", description = "팀 생성/수정/삭제 API")
@RestController
@RequestMapping("/teams")
class TeamController(
    private val teamService: TeamService,
) {

    @Operation(summary = "팀 생성", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "팀 생성 성공"),
        ApiResponse(responseCode = "400", description = "잘못된 요청"),
    )
    @PostMapping
    fun createTeam(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @RequestBody request: CreateTeamRequest,
    ): ResponseEntity<TeamResponse> =
        ResponseEntity.status(HttpStatus.CREATED).body(teamService.createTeam(userId, request))

    @Operation(summary = "내 팀 목록 조회", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @GetMapping
    fun getMyTeams(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
    ): ResponseEntity<List<TeamSummaryResponse>> =
        ResponseEntity.ok(teamService.getMyTeams(userId))

    @Operation(summary = "팀 상세 조회", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
        ApiResponse(responseCode = "404", description = "팀 없음"),
    )
    @GetMapping("/{teamId}")
    fun getTeam(
        @PathVariable teamId: Long,
    ): ResponseEntity<TeamResponse> =
        ResponseEntity.ok(teamService.getTeam(teamId))

    @Operation(summary = "팀 정보 수정", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "수정 성공"),
        ApiResponse(responseCode = "403", description = "권한 없음"),
        ApiResponse(responseCode = "404", description = "팀 없음"),
    )
    @PatchMapping("/{teamId}")
    fun updateTeam(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable teamId: Long,
        @RequestBody request: UpdateTeamRequest,
    ): ResponseEntity<TeamResponse> =
        ResponseEntity.ok(teamService.updateTeam(userId, teamId, request))

    @Operation(summary = "팀 삭제", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "삭제 성공"),
        ApiResponse(responseCode = "403", description = "권한 없음"),
        ApiResponse(responseCode = "404", description = "팀 없음"),
    )
    @DeleteMapping("/{teamId}")
    fun deleteTeam(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable teamId: Long,
    ): ResponseEntity<Void> {
        teamService.deleteTeam(userId, teamId)
        return ResponseEntity.noContent().build()
    }
}
