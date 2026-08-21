package com.cowork.project.domain.github.presentation.controller

import com.cowork.project.domain.github.presentation.data.request.AddProjectGithubRepoReqDto
import com.cowork.project.domain.github.presentation.data.response.ProjectGithubRepoResDto
import com.cowork.project.domain.github.service.AddProjectGithubRepoService
import com.cowork.project.domain.github.service.ListProjectGithubRepoLinksService
import com.cowork.project.domain.github.service.RemoveProjectGithubRepoService
import com.cowork.project.domain.project.presentation.data.request.SetProjectGithubWebhookChannelReqDto
import com.cowork.project.domain.project.service.ClearProjectGithubWebhookChannelService
import com.cowork.project.domain.project.service.SetProjectGithubWebhookChannelService
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
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@Tag(name = "GitHub 레포 연동", description = "프로젝트에 등록된 GitHub 레포지토리 관리 API (프로젝트당 여러 개 등록 가능)")
@RestController
@RequestMapping("/projects/{projectId}/github-repos")
class ProjectGithubRepoController(
    private val listProjectGithubRepoLinksService: ListProjectGithubRepoLinksService,
    private val addProjectGithubRepoService: AddProjectGithubRepoService,
    private val removeProjectGithubRepoService: RemoveProjectGithubRepoService,
    private val setProjectGithubWebhookChannelService: SetProjectGithubWebhookChannelService,
    private val clearProjectGithubWebhookChannelService: ClearProjectGithubWebhookChannelService,
) {

    @Operation(summary = "등록된 GitHub 레포 목록 조회", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
        ApiResponse(responseCode = "403", description = "팀 멤버 아님"),
        ApiResponse(responseCode = "404", description = "프로젝트 없음"),
    )
    @GetMapping
    fun list(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable projectId: Long,
    ): List<ProjectGithubRepoResDto> = listProjectGithubRepoLinksService.execute(userId, projectId)

    @Operation(summary = "GitHub 레포 등록", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "등록 성공"),
        ApiResponse(responseCode = "400", description = "유효하지 않은 GitHub URL"),
        ApiResponse(responseCode = "403", description = "권한 없음"),
        ApiResponse(responseCode = "404", description = "프로젝트 없음"),
        ApiResponse(responseCode = "409", description = "이미 팀 내 다른 프로젝트에 연결된 레포"),
    )
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun add(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable projectId: Long,
        @RequestBody request: AddProjectGithubRepoReqDto,
    ): ProjectGithubRepoResDto = addProjectGithubRepoService.execute(userId, projectId, request)

    @Operation(summary = "GitHub 레포 등록 해제", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "해제 성공"),
        ApiResponse(responseCode = "403", description = "권한 없음"),
        ApiResponse(responseCode = "404", description = "프로젝트 또는 레포 등록 없음"),
    )
    @DeleteMapping("/{repoId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun remove(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable projectId: Long,
        @PathVariable repoId: Long,
    ) {
        removeProjectGithubRepoService.execute(userId, projectId, repoId)
    }

    @Operation(summary = "GitHub 알림 채널 설정", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "설정 성공"),
        ApiResponse(responseCode = "400", description = "이 프로젝트 소속 채널이 아님"),
        ApiResponse(responseCode = "403", description = "권한 없음"),
        ApiResponse(responseCode = "404", description = "프로젝트, 레포 등록 또는 채널 없음"),
        ApiResponse(responseCode = "502", description = "채널 서비스 통신 오류"),
    )
    @PutMapping("/{repoId}/webhook-channel")
    fun setWebhookChannel(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable projectId: Long,
        @PathVariable repoId: Long,
        @RequestBody request: SetProjectGithubWebhookChannelReqDto,
    ): ProjectGithubRepoResDto = setProjectGithubWebhookChannelService.execute(userId, projectId, repoId, request)

    @Operation(summary = "GitHub 알림 채널 해제", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "해제 성공"),
        ApiResponse(responseCode = "403", description = "권한 없음"),
        ApiResponse(responseCode = "404", description = "프로젝트 또는 레포 등록 없음"),
    )
    @DeleteMapping("/{repoId}/webhook-channel")
    fun clearWebhookChannel(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable projectId: Long,
        @PathVariable repoId: Long,
    ): ProjectGithubRepoResDto = clearProjectGithubWebhookChannelService.execute(userId, projectId, repoId)
}
