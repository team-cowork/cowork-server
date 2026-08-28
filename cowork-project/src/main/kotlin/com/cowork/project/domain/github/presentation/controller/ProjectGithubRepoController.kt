package com.cowork.project.domain.github.presentation.controller

import com.cowork.project.domain.github.presentation.data.request.AddProjectGithubRepoReqDto
import com.cowork.project.domain.github.presentation.data.request.UpdateGithubLabelPolicyReqDto
import com.cowork.project.domain.github.presentation.data.response.GithubLabelPolicyResDto
import com.cowork.project.domain.github.presentation.data.response.ProjectGithubRepoResDto
import com.cowork.project.domain.github.service.AddProjectGithubRepoService
import com.cowork.project.domain.github.service.ListProjectGithubRepoLinksService
import com.cowork.project.domain.github.service.QueryGithubLabelPolicyService
import com.cowork.project.domain.github.service.RemoveProjectGithubRepoService
import com.cowork.project.domain.github.service.UpdateGithubLabelPolicyService
import com.cowork.project.domain.githubPreference.presentation.data.response.GithubLabelPolicyOperationAcceptedResDto
import com.cowork.project.domain.githubPreference.presentation.data.response.GithubLabelPolicyOperationResDto
import com.cowork.project.domain.githubPreference.service.QueryGithubLabelPolicyOperationService
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
    private val queryGithubLabelPolicyService: QueryGithubLabelPolicyService,
    private val updateGithubLabelPolicyService: UpdateGithubLabelPolicyService,
    private val queryGithubLabelPolicyOperationService: QueryGithubLabelPolicyOperationService,
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
        ApiResponse(responseCode = "503", description = "채널 projection 동기화 중"),
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

    @Operation(summary = "라벨 자동/수동 적용 정책 조회", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
        ApiResponse(responseCode = "400", description = "연결된 GitHub 레포지토리 URL이 올바르지 않음"),
        ApiResponse(responseCode = "403", description = "팀 멤버 아님"),
        ApiResponse(responseCode = "404", description = "프로젝트 또는 레포 등록 없음"),
        ApiResponse(responseCode = "503", description = "설정 projection 동기화 중"),
    )
    @GetMapping("/{repoId}/label-policy")
    fun getLabelPolicy(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable projectId: Long,
        @PathVariable repoId: Long,
    ): GithubLabelPolicyResDto = queryGithubLabelPolicyService.execute(userId, projectId, repoId)

    @Operation(summary = "라벨 자동/수동 적용 정책 변경", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "202", description = "비동기 변경 접수"),
        ApiResponse(responseCode = "400", description = "연결된 GitHub 레포지토리 URL이 올바르지 않음"),
        ApiResponse(responseCode = "403", description = "권한 없음"),
        ApiResponse(responseCode = "404", description = "프로젝트 또는 레포 등록 없음"),
        ApiResponse(responseCode = "409", description = "Idempotency-Key 재사용 요청 불일치"),
    )
    @PutMapping("/{repoId}/label-policy")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun updateLabelPolicy(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable projectId: Long,
        @PathVariable repoId: Long,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestBody request: UpdateGithubLabelPolicyReqDto,
    ): GithubLabelPolicyOperationAcceptedResDto =
        updateGithubLabelPolicyService.execute(userId, projectId, repoId, idempotencyKey, request)

    @Operation(summary = "라벨 정책 변경 작업 상태 조회", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
        ApiResponse(responseCode = "403", description = "팀 멤버 아님"),
        ApiResponse(responseCode = "404", description = "프로젝트, 레포 등록 또는 작업 없음"),
    )
    @GetMapping("/{repoId}/label-policy/operations/{operationId}")
    fun getLabelPolicyOperation(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable projectId: Long,
        @PathVariable repoId: Long,
        @PathVariable operationId: String,
    ): GithubLabelPolicyOperationResDto =
        queryGithubLabelPolicyOperationService.execute(userId, projectId, repoId, operationId)
}
