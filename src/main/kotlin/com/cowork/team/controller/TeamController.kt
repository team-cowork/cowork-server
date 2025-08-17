package com.cowork.team.controller

import com.cowork.global.security.AuthUser
import com.cowork.global.security.CurrentUser
import com.cowork.team.dto.CreateTeamRequest
import com.cowork.team.dto.TeamResponse
import com.cowork.team.dto.UpdateTeamRequest
import com.cowork.team.service.TeamService
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
@RequestMapping("/teams")
class TeamController(
    private val teamService: TeamService,
) {

    @PostMapping
    fun create(
        @CurrentUser user: AuthUser,
        @RequestBody request: CreateTeamRequest,
    ): TeamResponse = teamService.create(user.id, request)
}
