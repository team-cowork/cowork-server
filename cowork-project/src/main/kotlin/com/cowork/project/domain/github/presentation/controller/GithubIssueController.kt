package com.cowork.project.domain.github.presentation.controller

import com.cowork.project.domain.github.presentation.data.request.CreateGithubCommentReqDto
import com.cowork.project.domain.github.presentation.data.request.UpdateGithubCommentReqDto
import com.cowork.project.domain.github.presentation.data.request.UpdateGithubIssueLabelsReqDto
import com.cowork.project.domain.github.presentation.data.response.GithubCommentResDto
import com.cowork.project.domain.github.service.CreateGithubCommentService
import com.cowork.project.domain.github.service.DeleteGithubCommentService
import com.cowork.project.domain.github.service.GithubCommentParentType
import com.cowork.project.domain.github.service.ListGithubCommentsService
import com.cowork.project.domain.github.service.UpdateGithubCommentService
import com.cowork.project.domain.github.service.UpdateGithubIssueLabelsService
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

@Tag(name = "GitHub 이슈", description = "프로젝트에 연결된 GitHub 레포지토리의 이슈 조회/생성 API")
@RestController
@RequestMapping("/projects/{projectId}/github-repos/{repoId}/issues/{issueNumber}")
class GithubIssueController(
    private val updateGithubIssueLabelsService: UpdateGithubIssueLabelsService,
    private val listGithubCommentsService: ListGithubCommentsService,
    private val createGithubCommentService: CreateGithubCommentService,
    private val updateGithubCommentService: UpdateGithubCommentService,
    private val deleteGithubCommentService: DeleteGithubCommentService,
) {

    @Operation(
        summary = "이슈 라벨 적용/변경 (비동기)",
        description = "이슈의 라벨을 전체 교체한다. 라벨 자동 적용 정책이 꺼져있을 때 사람이 직접 라벨을 고르는 용도. " +
            "cowork-github-app에 Kafka(`github-app.issue-write.command`)로 REPLACE_LABELS 커맨드를 발행하고 " +
            "결과를 기다리지 않는다 — 이 응답은 요청이 접수됐다는 것만 의미한다.",
        security = [SecurityRequirement(name = "BearerAuth")],
    )
    @ApiResponses(
        ApiResponse(responseCode = "202", description = "요청 접수됨 (비동기 처리)"),
        ApiResponse(responseCode = "400", description = "연결된 GitHub 레포지토리 없음"),
        ApiResponse(responseCode = "403", description = "프로젝트 수정 권한 없음"),
        ApiResponse(responseCode = "404", description = "프로젝트 또는 이슈 없음"),
    )
    @PatchMapping("/labels")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun updateLabels(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable projectId: Long,
        @PathVariable repoId: Long,
        @PathVariable issueNumber: Int,
        @RequestBody request: UpdateGithubIssueLabelsReqDto,
    ) = updateGithubIssueLabelsService.execute(userId, projectId, repoId, issueNumber, request)

    @Operation(summary = "이슈 댓글 목록 조회", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
        ApiResponse(responseCode = "400", description = "연결된 GitHub 레포지토리 없음"),
        ApiResponse(responseCode = "403", description = "팀 멤버 아님"),
        ApiResponse(responseCode = "404", description = "프로젝트 또는 이슈 없음"),
        ApiResponse(responseCode = "502", description = "GitHub 연동 서버 통신 오류"),
    )
    @GetMapping("/comments")
    fun listComments(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable projectId: Long,
        @PathVariable repoId: Long,
        @PathVariable issueNumber: Int,
    ): List<GithubCommentResDto> = listGithubCommentsService.execute(userId, projectId, repoId, issueNumber)

    @Operation(
        summary = "이슈 댓글 작성 (비동기)",
        description = "cowork-github-app에 Kafka(`github-app.issue-write.command`)로 CREATE_COMMENT 커맨드를 " +
            "발행하고 결과를 기다리지 않는다 — 이 응답은 요청이 접수됐다는 것만 의미하며, 생성된 댓글 정보는 " +
            "이 API로 확인할 수 없다.",
        security = [SecurityRequirement(name = "BearerAuth")],
    )
    @ApiResponses(
        ApiResponse(responseCode = "202", description = "요청 접수됨 (비동기 처리)"),
        ApiResponse(responseCode = "400", description = "연결된 GitHub 레포지토리 없음 또는 GitHub 계정 미연동"),
        ApiResponse(responseCode = "403", description = "팀 멤버 아님"),
        ApiResponse(responseCode = "404", description = "프로젝트 또는 이슈 없음"),
    )
    @PostMapping("/comments")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun createComment(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable projectId: Long,
        @PathVariable repoId: Long,
        @PathVariable issueNumber: Int,
        @RequestBody request: CreateGithubCommentReqDto,
    ) = createGithubCommentService.execute(userId, projectId, repoId, GithubCommentParentType.ISSUE, issueNumber, request)

    @Operation(
        summary = "이슈 댓글 수정 (비동기)",
        description = "cowork-github-app에 Kafka(`github-app.issue-write.command`)로 UPDATE_COMMENT 커맨드를 " +
            "발행하고 결과를 기다리지 않는다 — 이 응답은 요청이 접수됐다는 것만 의미한다.",
        security = [SecurityRequirement(name = "BearerAuth")],
    )
    @ApiResponses(
        ApiResponse(responseCode = "202", description = "요청 접수됨 (비동기 처리)"),
        ApiResponse(responseCode = "400", description = "연결된 GitHub 레포지토리 없음 또는 GitHub 계정 미연동"),
        ApiResponse(responseCode = "403", description = "본인이 작성한 댓글이 아니며 프로젝트 수정 권한도 없음"),
        ApiResponse(responseCode = "404", description = "프로젝트 또는 댓글 없음"),
        ApiResponse(responseCode = "502", description = "GitHub 연동 서버 통신 오류"),
    )
    @PatchMapping("/comments/{commentId}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun updateComment(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable projectId: Long,
        @PathVariable repoId: Long,
        @PathVariable issueNumber: Int,
        @PathVariable commentId: Long,
        @RequestBody request: UpdateGithubCommentReqDto,
    ) = updateGithubCommentService.execute(userId, projectId, repoId, commentId, request)

    @Operation(
        summary = "이슈 댓글 삭제 (비동기)",
        description = "cowork-github-app에 Kafka(`github-app.issue-write.command`)로 DELETE_COMMENT 커맨드를 " +
            "발행하고 결과를 기다리지 않는다 — 이 응답은 요청이 접수됐다는 것만 의미한다.",
        security = [SecurityRequirement(name = "BearerAuth")],
    )
    @ApiResponses(
        ApiResponse(responseCode = "202", description = "요청 접수됨 (비동기 처리)"),
        ApiResponse(responseCode = "400", description = "연결된 GitHub 레포지토리 없음"),
        ApiResponse(responseCode = "403", description = "본인이 작성한 댓글이 아니며 프로젝트 수정 권한도 없음"),
        ApiResponse(responseCode = "404", description = "프로젝트 또는 댓글 없음"),
        ApiResponse(responseCode = "502", description = "GitHub 연동 서버 통신 오류"),
    )
    @DeleteMapping("/comments/{commentId}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun deleteComment(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable projectId: Long,
        @PathVariable repoId: Long,
        @PathVariable issueNumber: Int,
        @PathVariable commentId: Long,
    ) = deleteGithubCommentService.execute(userId, projectId, repoId, commentId)
}
