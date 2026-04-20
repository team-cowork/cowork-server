package com.cowork.team.controller

import com.cowork.team.dto.CreateTeamRequest
import com.cowork.team.dto.TeamResponse
import com.cowork.team.dto.TeamSummaryResponse
import com.cowork.team.dto.UpdateTeamRequest
import com.cowork.team.service.TeamService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/teams")
class TeamController(
    private val teamService: TeamService,
) {

    @PostMapping
    fun createTeam(
        @RequestHeader("X-User-Id") userId: Long,
        @RequestBody request: CreateTeamRequest,
    ): ResponseEntity<TeamResponse> =
        ResponseEntity.status(HttpStatus.CREATED).body(teamService.createTeam(userId, request))

    @GetMapping
    fun getMyTeams(
        @RequestHeader("X-User-Id") userId: Long,
    ): ResponseEntity<List<TeamSummaryResponse>> =
        ResponseEntity.ok(teamService.getMyTeams(userId))

    @GetMapping("/{teamId}")
    fun getTeam(
        @PathVariable teamId: Long,
    ): ResponseEntity<TeamResponse> =
        ResponseEntity.ok(teamService.getTeam(teamId))

    @PatchMapping("/{teamId}")
    fun updateTeam(
        @RequestHeader("X-User-Id") userId: Long,
        @PathVariable teamId: Long,
        @RequestBody request: UpdateTeamRequest,
    ): ResponseEntity<TeamResponse> =
        ResponseEntity.ok(teamService.updateTeam(userId, teamId, request))

    @DeleteMapping("/{teamId}")
    fun deleteTeam(
        @RequestHeader("X-User-Id") userId: Long,
        @PathVariable teamId: Long,
    ): ResponseEntity<Void> {
        teamService.deleteTeam(userId, teamId)
        return ResponseEntity.noContent().build()
    }
}
