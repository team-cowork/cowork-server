package com.cowork.project.domain.github.presentation.controller

import com.cowork.project.domain.github.presentation.data.request.CreateGithubCommentReqDto
import com.cowork.project.domain.github.presentation.data.request.UpdateGithubCommentReqDto
import com.cowork.project.domain.github.presentation.data.response.GithubCommentResDto
import com.cowork.project.domain.github.presentation.data.response.GithubPullRequestFileResDto
import com.cowork.project.domain.github.presentation.data.response.GithubPullRequestResDto
import com.cowork.project.domain.github.service.ApprovePullRequestService
import com.cowork.project.domain.github.service.CreateGithubCommentService
import com.cowork.project.domain.github.service.DeleteGithubCommentService
import com.cowork.project.domain.github.service.GithubCommentParentType
import com.cowork.project.domain.github.service.ListGithubCommentsService
import com.cowork.project.domain.github.service.ListPullRequestFilesService
import com.cowork.project.domain.github.service.MergePullRequestService
import com.cowork.project.domain.github.service.QueryPullRequestDetailService
import com.cowork.project.domain.github.service.UpdateGithubCommentService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@Tag(name = "GitHub PR", description = "프로젝트에 연결된 GitHub 레포지토리의 PR 조회/머지/승인 API")
@RestController
@RequestMapping("/projects/{projectId}/github-repos/{repoId}/pulls/{prNumber}")
class GithubPullRequestController(
    private val queryPullRequestDetailService: QueryPullRequestDetailService,
    private val listPullRequestFilesService: ListPullRequestFilesService,
    private val mergePullRequestService: MergePullRequestService,
    private val approvePullRequestService: ApprovePullRequestService,
    private val listGithubCommentsService: ListGithubCommentsService,
    private val createGithubCommentService: CreateGithubCommentService,
    private val updateGithubCommentService: UpdateGithubCommentService,
    private val deleteGithubCommentService: DeleteGithubCommentService,
) {

    @Operation(summary = "PR 상세 조회", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
        ApiResponse(responseCode = "400", description = "연결된 GitHub 레포지토리 없음"),
        ApiResponse(responseCode = "403", description = "팀 멤버 아님"),
        ApiResponse(responseCode = "404", description = "프로젝트 또는 PR 없음"),
        ApiResponse(responseCode = "502", description = "GitHub 연동 서버 통신 오류"),
    )
    @GetMapping
    fun getDetail(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable projectId: Long,
        @PathVariable repoId: Long,
        @PathVariable prNumber: Int,
    ): GithubPullRequestResDto = queryPullRequestDetailService.execute(userId, projectId, repoId, prNumber)

    @Operation(summary = "PR 파일 변경 목록 조회", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
        ApiResponse(responseCode = "400", description = "연결된 GitHub 레포지토리 없음"),
        ApiResponse(responseCode = "403", description = "팀 멤버 아님"),
        ApiResponse(responseCode = "404", description = "프로젝트 또는 PR 없음"),
        ApiResponse(responseCode = "502", description = "GitHub 연동 서버 통신 오류"),
    )
    @GetMapping("/files")
    fun getFiles(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable projectId: Long,
        @PathVariable repoId: Long,
        @PathVariable prNumber: Int,
    ): List<GithubPullRequestFileResDto> = listPullRequestFilesService.execute(userId, projectId, repoId, prNumber)

    @Operation(
        summary = "PR 머지 요청 (squash, 비동기)",
        description = "cowork-github-app에 Kafka(`github.pr.merge`)로 머지를 요청하고 결과를 기다리지 않는다 — " +
            "이 응답은 요청이 접수됐다는 것만 의미하며, 실제 머지 성공/실패(충돌, 이미 머지됨 등)는 이 API로 확인할 수 없다. " +
            "처리 결과는 cowork-github-app이 `github.pr.merge.result` 토픽으로 발행하지만, " +
            "cowork-project는 현재 이 토픽을 구독하지 않는다 — 호출자에게 결과를 전달해야 한다면 " +
            "이 토픽을 구독하는 컨슈머와 별도의 알림/조회 경로를 추가로 구현해야 한다.",
        security = [SecurityRequirement(name = "BearerAuth")],
    )
    @ApiResponses(
        ApiResponse(responseCode = "202", description = "요청 접수됨 (비동기 처리, 머지 결과는 이 API로 확인 불가)"),
        ApiResponse(responseCode = "400", description = "연결된 GitHub 레포지토리 없음 또는 GitHub 계정 미연동"),
        ApiResponse(responseCode = "403", description = "프로젝트 수정 권한 없음"),
        ApiResponse(responseCode = "404", description = "프로젝트 또는 PR 없음"),
    )
    @PostMapping("/merge")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun merge(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable projectId: Long,
        @PathVariable repoId: Long,
        @PathVariable prNumber: Int,
    ) = mergePullRequestService.execute(userId, projectId, repoId, prNumber)

    @Operation(
        summary = "PR 승인 요청 (비동기)",
        description = "cowork-github-app에 Kafka(`github.pr.approve`)로 승인을 요청하고 결과를 기다리지 않는다 — " +
            "이 응답은 요청이 접수됐다는 것만 의미하며, 본인 PR 승인 시도 등으로 인한 실패는 이 API로 확인할 수 없다. " +
            "처리 결과는 cowork-github-app이 `github.pr.approve.result` 토픽으로 발행하지만, " +
            "cowork-project는 현재 이 토픽을 구독하지 않는다 — 호출자에게 결과를 전달해야 한다면 " +
            "이 토픽을 구독하는 컨슈머와 별도의 알림/조회 경로를 추가로 구현해야 한다.",
        security = [SecurityRequirement(name = "BearerAuth")],
    )
    @ApiResponses(
        ApiResponse(responseCode = "202", description = "요청 접수됨 (비동기 처리, 승인 결과는 이 API로 확인 불가)"),
        ApiResponse(responseCode = "400", description = "연결된 GitHub 레포지토리 없음 또는 GitHub 계정 미연동"),
        ApiResponse(responseCode = "403", description = "프로젝트 수정 권한 없음"),
        ApiResponse(responseCode = "404", description = "프로젝트 또는 PR 없음"),
    )
    @PostMapping("/approve")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun approve(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable projectId: Long,
        @PathVariable repoId: Long,
        @PathVariable prNumber: Int,
    ) = approvePullRequestService.execute(userId, projectId, repoId, prNumber)

    @Operation(summary = "PR 댓글 목록 조회", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
        ApiResponse(responseCode = "400", description = "연결된 GitHub 레포지토리 없음"),
        ApiResponse(responseCode = "403", description = "팀 멤버 아님"),
        ApiResponse(responseCode = "404", description = "프로젝트 또는 PR 없음"),
        ApiResponse(responseCode = "502", description = "GitHub 연동 서버 통신 오류"),
    )
    @GetMapping("/comments")
    fun listComments(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable projectId: Long,
        @PathVariable repoId: Long,
        @PathVariable prNumber: Int,
    ): List<GithubCommentResDto> = listGithubCommentsService.execute(userId, projectId, repoId, prNumber)

    @Operation(summary = "PR 댓글 작성", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "작성 성공"),
        ApiResponse(responseCode = "400", description = "연결된 GitHub 레포지토리 없음 또는 GitHub 계정 미연동"),
        ApiResponse(responseCode = "403", description = "팀 멤버 아님"),
        ApiResponse(responseCode = "404", description = "프로젝트 또는 PR 없음"),
        ApiResponse(responseCode = "502", description = "GitHub 연동 서버 통신 오류"),
    )
    @PostMapping("/comments")
    @ResponseStatus(HttpStatus.CREATED)
    fun createComment(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable projectId: Long,
        @PathVariable repoId: Long,
        @PathVariable prNumber: Int,
        @RequestBody request: CreateGithubCommentReqDto,
    ): GithubCommentResDto =
        createGithubCommentService.execute(userId, projectId, repoId, GithubCommentParentType.PULL_REQUEST, prNumber, request)

    @Operation(summary = "PR 댓글 수정", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "수정 성공"),
        ApiResponse(responseCode = "400", description = "연결된 GitHub 레포지토리 없음 또는 GitHub 계정 미연동"),
        ApiResponse(responseCode = "403", description = "본인이 작성한 댓글이 아니며 프로젝트 수정 권한도 없음"),
        ApiResponse(responseCode = "404", description = "프로젝트 또는 댓글 없음"),
        ApiResponse(responseCode = "502", description = "GitHub 연동 서버 통신 오류"),
    )
    @PatchMapping("/comments/{commentId}")
    fun updateComment(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable projectId: Long,
        @PathVariable repoId: Long,
        @PathVariable prNumber: Int,
        @PathVariable commentId: Long,
        @RequestBody request: UpdateGithubCommentReqDto,
    ): GithubCommentResDto = updateGithubCommentService.execute(userId, projectId, repoId, commentId, request)

    @Operation(summary = "PR 댓글 삭제", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "삭제 성공"),
        ApiResponse(responseCode = "400", description = "연결된 GitHub 레포지토리 없음"),
        ApiResponse(responseCode = "403", description = "본인이 작성한 댓글이 아니며 프로젝트 수정 권한도 없음"),
        ApiResponse(responseCode = "404", description = "프로젝트 또는 댓글 없음"),
        ApiResponse(responseCode = "502", description = "GitHub 연동 서버 통신 오류"),
    )
    @DeleteMapping("/comments/{commentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteComment(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable projectId: Long,
        @PathVariable repoId: Long,
        @PathVariable prNumber: Int,
        @PathVariable commentId: Long,
    ) = deleteGithubCommentService.execute(userId, projectId, repoId, commentId)
}
