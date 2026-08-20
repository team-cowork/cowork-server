package com.cowork.team.domain.team.presentation.controller

import com.cowork.team.domain.team.presentation.data.response.GithubInstallUrlResponse
import com.cowork.team.domain.team.service.DisconnectGithubService
import com.cowork.team.domain.team.service.GenerateGithubInstallUrlService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@Tag(name = "팀 GitHub 연동", description = "팀 단위 GitHub App 설치/연동 해제 API")
@RestController
@RequestMapping("/teams/{teamId}")
class TeamGithubController(
    private val generateGithubInstallUrlService: GenerateGithubInstallUrlService,
    private val disconnectGithubService: DisconnectGithubService,
) {

    @Operation(summary = "GitHub App 설치 URL 발급", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "발급 성공"),
        ApiResponse(responseCode = "403", description = "팀 멤버가 아님"),
        ApiResponse(responseCode = "404", description = "팀 없음"),
    )
    @GetMapping("/github/install-url")
    fun getInstallUrl(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable teamId: Long,
    ): GithubInstallUrlResponse = generateGithubInstallUrlService.execute(userId, teamId)

    @Operation(summary = "GitHub App 연동 해제", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "해제 성공"),
        ApiResponse(responseCode = "403", description = "권한 없음"),
        ApiResponse(responseCode = "404", description = "팀 없음"),
    )
    @DeleteMapping("/github-installation")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun disconnectGithub(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable teamId: Long,
    ) {
        disconnectGithubService.execute(userId, teamId)
    }
}
