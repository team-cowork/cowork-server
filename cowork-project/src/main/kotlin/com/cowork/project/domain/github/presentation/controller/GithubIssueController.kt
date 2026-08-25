package com.cowork.project.domain.github.presentation.controller

import com.cowork.project.domain.github.presentation.data.request.CreateGithubCommentReqDto
import com.cowork.project.domain.github.presentation.data.request.UpdateGithubCommentReqDto
import com.cowork.project.domain.github.presentation.data.request.UpdateGithubIssueLabelsReqDto
import com.cowork.project.domain.github.presentation.data.response.GithubCommentResDto
import com.cowork.project.domain.github.presentation.data.response.GithubIssueResDto
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
        summary = "이슈 라벨 적용/변경",
        description = "이슈의 라벨을 전체 교체한다. 라벨 자동 적용 정책이 꺼져있을 때 사람이 직접 라벨을 고르는 용도.",
        security = [SecurityRequirement(name = "BearerAuth")],
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "적용 성공"),
        ApiResponse(responseCode = "400", description = "연결된 GitHub 레포지토리 없음"),
        ApiResponse(responseCode = "403", description = "프로젝트 수정 권한 없음"),
        ApiResponse(responseCode = "404", description = "프로젝트 또는 이슈 없음"),
        ApiResponse(responseCode = "502", description = "GitHub 연동 서버 통신 오류"),
    )
    @PatchMapping("/labels")
    fun updateLabels(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable projectId: Long,
        @PathVariable repoId: Long,
        @PathVariable issueNumber: Int,
        @RequestBody request: UpdateGithubIssueLabelsReqDto,
    ): GithubIssueResDto = updateGithubIssueLabelsService.execute(userId, projectId, repoId, issueNumber, request)

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

    @Operation(summary = "이슈 댓글 작성", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "작성 성공"),
        ApiResponse(responseCode = "400", description = "연결된 GitHub 레포지토리 없음 또는 GitHub 계정 미연동"),
        ApiResponse(responseCode = "403", description = "팀 멤버 아님"),
        ApiResponse(responseCode = "404", description = "프로젝트 또는 이슈 없음"),
        ApiResponse(responseCode = "502", description = "GitHub 연동 서버 통신 오류"),
    )
    @PostMapping("/comments")
    @ResponseStatus(HttpStatus.CREATED)
    fun createComment(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable projectId: Long,
        @PathVariable repoId: Long,
        @PathVariable issueNumber: Int,
        @RequestBody request: CreateGithubCommentReqDto,
    ): GithubCommentResDto =
        createGithubCommentService.execute(userId, projectId, repoId, GithubCommentParentType.ISSUE, issueNumber, request)

    @Operation(summary = "이슈 댓글 수정", security = [SecurityRequirement(name = "BearerAuth")])
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
        @PathVariable issueNumber: Int,
        @PathVariable commentId: Long,
        @RequestBody request: UpdateGithubCommentReqDto,
    ): GithubCommentResDto = updateGithubCommentService.execute(userId, projectId, repoId, commentId, request)

    @Operation(summary = "이슈 댓글 삭제", security = [SecurityRequirement(name = "BearerAuth")])
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
        @PathVariable issueNumber: Int,
        @PathVariable commentId: Long,
    ) = deleteGithubCommentService.execute(userId, projectId, repoId, commentId)
}
