package com.cowork.team.controller

import com.cowork.global.security.AuthUser
import com.cowork.global.security.CurrentUser
import com.cowork.team.dto.CreateTeamRequest
import com.cowork.team.dto.TeamResponse
import com.cowork.team.dto.UpdateTeamRequest
import com.cowork.team.service.TeamService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "팀")
@RestController
@RequestMapping("/teams")
class TeamController(
    private val teamService: TeamService,
) {

    @Operation(summary = "팀 생성")
    @PostMapping
    fun create(
        @CurrentUser user: AuthUser,
        @RequestBody request: CreateTeamRequest,
    ): TeamResponse = teamService.create(user.id, request)

    @Operation(summary = "내가 속한 팀 목록 조회")
    @GetMapping
    fun getMyTeams(@CurrentUser user: AuthUser): List<TeamResponse> = teamService.getMyTeams(user.id)

    @Operation(summary = "팀 조회")
    @GetMapping("/{teamId}")
    fun getTeam(@CurrentUser user: AuthUser, @PathVariable teamId: Long): TeamResponse =
        teamService.getTeam(teamId, user.id)

    @Operation(summary = "팀 수정")
    @PatchMapping("/{teamId}")
    fun update(
        @CurrentUser user: AuthUser,
        @PathVariable teamId: Long,
        @RequestBody request: UpdateTeamRequest,
    ): TeamResponse = teamService.update(teamId, user.id, request)

    @Operation(summary = "팀 삭제")
    @DeleteMapping("/{teamId}")
    fun delete(@CurrentUser user: AuthUser, @PathVariable teamId: Long) = teamService.delete(teamId, user.id)
}
