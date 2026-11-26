package com.cowork.project.domain.github.presentation.controller

import com.cowork.project.domain.github.presentation.data.request.CreateGithubCommentReqDto
import com.cowork.project.domain.github.presentation.data.request.GithubTimelineQueryReqDto
import com.cowork.project.domain.github.presentation.data.request.UpdateGithubCommentReqDto
import com.cowork.project.domain.github.presentation.data.response.GithubCommentResDto
import com.cowork.project.domain.github.presentation.data.response.GithubPullRequestActionResDto
import com.cowork.project.domain.github.presentation.data.response.GithubPullRequestFileResDto
import com.cowork.project.domain.github.presentation.data.response.GithubPullRequestResDto
import com.cowork.project.domain.github.presentation.data.response.GithubTimelineResDto
import com.cowork.project.domain.github.repository.GithubTimelineParent
import com.cowork.project.domain.github.service.ApprovePullRequestService
import com.cowork.project.domain.github.service.ClosePullRequestService
import com.cowork.project.domain.github.service.CreateGithubCommentService
import com.cowork.project.domain.github.service.DeleteGithubCommentService
import com.cowork.project.domain.github.service.GithubCommentParentType
import com.cowork.project.domain.github.service.ListGithubCommentsService
import com.cowork.project.domain.github.service.ListPullRequestFilesService
import com.cowork.project.domain.github.service.MergePullRequestService
import com.cowork.project.domain.github.service.QueryGithubTimelineService
import com.cowork.project.domain.github.service.QueryPullRequestDetailService
import com.cowork.project.domain.github.service.UpdateGithubCommentService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springdoc.core.annotations.ParameterObject
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import team.themoment.sdk.response.CommonApiResponse

@Tag(name = "GitHub PR", description = "프로젝트에 연결된 GitHub 저장소의 PR 조회/머지/승인/닫기 API")
@ApiResponses(
    ApiResponse(
        responseCode = "401",
        description = "액세스 토큰이 없거나 유효하지 않음",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = CommonApiResponse::class))],
    ),
    ApiResponse(
        responseCode = "503",
        description = "프로젝트 생성이나 권한 변경 직후 권한 정보가 아직 동기화되지 않음.",
        content = [Content(schema = Schema(implementation = CommonApiResponse::class))],
    ),
    ApiResponse(
        responseCode = "default",
        description = "기타 오류",
        content = [Content(schema = Schema(implementation = CommonApiResponse::class))],
    ),
)
@RestController
@RequestMapping("/projects/{projectId}/github-repos/{repoId}/pulls/{prNumber}")
class GithubPullRequestController(
    private val queryGithubTimelineService: QueryGithubTimelineService,
    private val queryPullRequestDetailService: QueryPullRequestDetailService,
    private val listPullRequestFilesService: ListPullRequestFilesService,
    private val mergePullRequestService: MergePullRequestService,
    private val approvePullRequestService: ApprovePullRequestService,
    private val closePullRequestService: ClosePullRequestService,
    private val listGithubCommentsService: ListGithubCommentsService,
    private val createGithubCommentService: CreateGithubCommentService,
    private val updateGithubCommentService: UpdateGithubCommentService,
    private val deleteGithubCommentService: DeleteGithubCommentService,
) {

    @Operation(
        summary = "PR 활동 이력 조회",
        description = "발생 시각이 같은 활동은 식별자 순으로 반환합니다. 다음 페이지부터 첫 페이지 응답의 snapshotId를 보내고 size를 유지하십시오.",
        security = [SecurityRequirement(name = "BearerAuth")],
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "활동 이력 조회 성공"),
        ApiResponse(responseCode = "400", description = "잘못된 번호, 페이지 범위 또는 다음 페이지의 스냅샷 ID 누락"),
        ApiResponse(responseCode = "403", description = "프로젝트 읽기 권한 없음"),
        ApiResponse(responseCode = "404", description = "프로젝트, 저장소 또는 PR 없음"),
        ApiResponse(responseCode = "409", description = "요청한 스냅샷 만료"),
        ApiResponse(responseCode = "503", description = "동기화 진행 중. 잠시 후 다시 조회하십시오."),
    )
    @GetMapping("/timeline")
    fun getTimeline(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable projectId: Long,
        @PathVariable repoId: Long,
        @PathVariable prNumber: Int,
        @Valid @ParameterObject @ModelAttribute queryReq: GithubTimelineQueryReqDto,
    ): GithubTimelineResDto = queryGithubTimelineService.execute(
        userId,
        projectId,
        repoId,
        GithubTimelineParent.PULL_REQUEST,
        prNumber,
        queryReq.page,
        queryReq.size,
        queryReq.snapshotId,
    )

    @Operation(
        summary = "PR 상세 조회",
        description = "머지 가능 여부, 머지 가능 상태, 리뷰 결정 상태는 열린 PR만 GitHub에서 실시간으로 조회합니다. " +
            "머지·닫힌 PR이거나 실시간 조회에 실패하면 이 값들은 null입니다.",
        security = [SecurityRequirement(name = "BearerAuth")],
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
        ApiResponse(
            responseCode = "400",
            description = "등록된 GitHub 저장소 URL 형식 오류",
            content = [
                Content(mediaType = "application/json", schema = Schema(implementation = CommonApiResponse::class)),
            ],
        ),
        ApiResponse(
            responseCode = "403",
            description = "프로젝트 읽기 권한 없음",
            content = [
                Content(mediaType = "application/json", schema = Schema(implementation = CommonApiResponse::class)),
            ],
        ),
        ApiResponse(
            responseCode = "404",
            description = "프로젝트, 등록된 저장소 또는 PR 없음",
            content = [
                Content(mediaType = "application/json", schema = Schema(implementation = CommonApiResponse::class)),
            ],
        ),
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
        ApiResponse(
            responseCode = "400",
            description = "등록된 GitHub 저장소 URL 형식 오류",
            content = [
                Content(mediaType = "application/json", schema = Schema(implementation = CommonApiResponse::class)),
            ],
        ),
        ApiResponse(
            responseCode = "403",
            description = "프로젝트 읽기 권한 없음",
            content = [
                Content(mediaType = "application/json", schema = Schema(implementation = CommonApiResponse::class)),
            ],
        ),
        ApiResponse(
            responseCode = "404",
            description = "프로젝트, 등록된 저장소 또는 PR 없음",
            content = [
                Content(mediaType = "application/json", schema = Schema(implementation = CommonApiResponse::class)),
            ],
        ),
        ApiResponse(
            responseCode = "502",
            description = "GitHub 연동 오류",
            content = [
                Content(mediaType = "application/json", schema = Schema(implementation = CommonApiResponse::class)),
            ],
        ),
    )
    @GetMapping("/files")
    fun getFiles(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable projectId: Long,
        @PathVariable repoId: Long,
        @PathVariable prNumber: Int,
    ): List<GithubPullRequestFileResDto> = listPullRequestFilesService.execute(userId, projectId, repoId, prNumber)

    @Operation(
        summary = "PR 병합",
        description = "커밋을 하나로 합쳐 병합하는 squash 방식을 사용합니다. GitHub에 병합이 반영된 뒤 응답합니다.",
        security = [SecurityRequirement(name = "BearerAuth")],
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "GitHub 반영 성공"),
        ApiResponse(
            responseCode = "400",
            description = "잘못된 저장소 URL 또는 연결된 GitHub 계정 없음",
            content = [
                Content(mediaType = "application/json", schema = Schema(implementation = CommonApiResponse::class)),
            ],
        ),
        ApiResponse(
            responseCode = "403",
            description = "프로젝트 수정 권한 없음 또는 GitHub 저장소 쓰기 권한 없음",
            content = [
                Content(mediaType = "application/json", schema = Schema(implementation = CommonApiResponse::class)),
            ],
        ),
        ApiResponse(
            responseCode = "404",
            description = "프로젝트, 등록된 저장소 또는 PR 없음",
            content = [
                Content(mediaType = "application/json", schema = Schema(implementation = CommonApiResponse::class)),
            ],
        ),
        ApiResponse(
            responseCode = "409",
            description = "이미 병합되었거나 닫힌 PR, 또는 충돌 등으로 병합할 수 없는 PR",
            content = [
                Content(mediaType = "application/json", schema = Schema(implementation = CommonApiResponse::class)),
            ],
        ),
        ApiResponse(
            responseCode = "502",
            description = "GitHub 연동 오류",
            content = [
                Content(mediaType = "application/json", schema = Schema(implementation = CommonApiResponse::class)),
            ],
        ),
    )
    @PostMapping("/merge")
    fun merge(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable projectId: Long,
        @PathVariable repoId: Long,
        @PathVariable prNumber: Int,
    ): GithubPullRequestActionResDto = mergePullRequestService.execute(userId, projectId, repoId, prNumber)

    @Operation(
        summary = "PR 승인",
        description = "GitHub에 승인 리뷰가 반영된 뒤 응답합니다. 승인은 GitHub App 설치 토큰으로 남기므로 " +
            "GitHub에는 요청한 사용자가 아니라 GitHub App 봇이 승인한 것으로 기록됩니다.",
        security = [SecurityRequirement(name = "BearerAuth")],
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "GitHub 반영 성공"),
        ApiResponse(
            responseCode = "400",
            description = "잘못된 저장소 URL 또는 연결된 GitHub 계정 없음",
            content = [
                Content(mediaType = "application/json", schema = Schema(implementation = CommonApiResponse::class)),
            ],
        ),
        ApiResponse(
            responseCode = "403",
            description = "프로젝트 수정 권한 없음, GitHub 저장소 쓰기 권한 없음 또는 본인이 작성한 PR",
            content = [
                Content(mediaType = "application/json", schema = Schema(implementation = CommonApiResponse::class)),
            ],
        ),
        ApiResponse(
            responseCode = "404",
            description = "프로젝트, 등록된 저장소 또는 PR 없음",
            content = [
                Content(mediaType = "application/json", schema = Schema(implementation = CommonApiResponse::class)),
            ],
        ),
        ApiResponse(
            responseCode = "409",
            description = "이미 병합되었거나 닫힌 PR",
            content = [
                Content(mediaType = "application/json", schema = Schema(implementation = CommonApiResponse::class)),
            ],
        ),
        ApiResponse(
            responseCode = "502",
            description = "GitHub 연동 오류",
            content = [
                Content(mediaType = "application/json", schema = Schema(implementation = CommonApiResponse::class)),
            ],
        ),
    )
    @PostMapping("/approve")
    fun approve(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable projectId: Long,
        @PathVariable repoId: Long,
        @PathVariable prNumber: Int,
    ): GithubPullRequestActionResDto = approvePullRequestService.execute(userId, projectId, repoId, prNumber)

    @Operation(
        summary = "PR 닫기",
        description = "PR을 병합하지 않고 닫습니다. GitHub에 반영된 뒤 응답합니다.",
        security = [SecurityRequirement(name = "BearerAuth")],
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "GitHub 반영 성공"),
        ApiResponse(
            responseCode = "400",
            description = "잘못된 저장소 URL 또는 연결된 GitHub 계정 없음",
            content = [
                Content(mediaType = "application/json", schema = Schema(implementation = CommonApiResponse::class)),
            ],
        ),
        ApiResponse(
            responseCode = "403",
            description = "프로젝트 수정 권한 없음 또는 GitHub 저장소 쓰기 권한 없음",
            content = [
                Content(mediaType = "application/json", schema = Schema(implementation = CommonApiResponse::class)),
            ],
        ),
        ApiResponse(
            responseCode = "404",
            description = "프로젝트, 등록된 저장소 또는 PR 없음",
            content = [
                Content(mediaType = "application/json", schema = Schema(implementation = CommonApiResponse::class)),
            ],
        ),
        ApiResponse(
            responseCode = "409",
            description = "이미 병합되었거나 닫힌 PR",
            content = [
                Content(mediaType = "application/json", schema = Schema(implementation = CommonApiResponse::class)),
            ],
        ),
        ApiResponse(
            responseCode = "502",
            description = "GitHub 연동 오류",
            content = [
                Content(mediaType = "application/json", schema = Schema(implementation = CommonApiResponse::class)),
            ],
        ),
    )
    @PostMapping("/close")
    fun close(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable projectId: Long,
        @PathVariable repoId: Long,
        @PathVariable prNumber: Int,
    ): GithubPullRequestActionResDto = closePullRequestService.execute(userId, projectId, repoId, prNumber)

    @Operation(summary = "PR 댓글 목록 조회", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
        ApiResponse(
            responseCode = "400",
            description = "등록된 GitHub 저장소 URL 형식 오류",
            content = [
                Content(mediaType = "application/json", schema = Schema(implementation = CommonApiResponse::class)),
            ],
        ),
        ApiResponse(
            responseCode = "403",
            description = "프로젝트 읽기 권한 없음",
            content = [
                Content(mediaType = "application/json", schema = Schema(implementation = CommonApiResponse::class)),
            ],
        ),
        ApiResponse(
            responseCode = "404",
            description = "프로젝트, 등록된 저장소 또는 PR 없음",
            content = [
                Content(mediaType = "application/json", schema = Schema(implementation = CommonApiResponse::class)),
            ],
        ),
        ApiResponse(
            responseCode = "502",
            description = "GitHub 연동 오류",
            content = [
                Content(mediaType = "application/json", schema = Schema(implementation = CommonApiResponse::class)),
            ],
        ),
    )
    @GetMapping("/comments")
    fun listComments(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable projectId: Long,
        @PathVariable repoId: Long,
        @PathVariable prNumber: Int,
    ): List<GithubCommentResDto> = listGithubCommentsService.execute(userId, projectId, repoId, prNumber)

    @Operation(
        summary = "PR 댓글 작성 요청",
        description = "응답은 요청 접수만 확인합니다. 생성된 댓글은 댓글 목록에서 확인할 수 있습니다.",
        security = [SecurityRequirement(name = "BearerAuth")],
    )
    @ApiResponses(
        ApiResponse(responseCode = "202", description = "요청 접수 (응답 본문 없음)", content = [Content()]),
        ApiResponse(
            responseCode = "400",
            description = "잘못된 저장소 URL 또는 연결된 GitHub 계정 없음",
            content = [
                Content(mediaType = "application/json", schema = Schema(implementation = CommonApiResponse::class)),
            ],
        ),
        ApiResponse(
            responseCode = "403",
            description = "프로젝트 읽기 권한 없음",
            content = [
                Content(mediaType = "application/json", schema = Schema(implementation = CommonApiResponse::class)),
            ],
        ),
        ApiResponse(
            responseCode = "404",
            description = "프로젝트 또는 등록된 저장소 없음",
            content = [
                Content(mediaType = "application/json", schema = Schema(implementation = CommonApiResponse::class)),
            ],
        ),
    )
    @PostMapping("/comments")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun createComment(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable projectId: Long,
        @PathVariable repoId: Long,
        @PathVariable prNumber: Int,
        @RequestBody request: CreateGithubCommentReqDto,
    ) = createGithubCommentService.execute(
        userId,
        projectId,
        repoId,
        GithubCommentParentType.PULL_REQUEST,
        prNumber,
        request,
    )

    @Operation(
        summary = "PR 댓글 수정 요청",
        description = "응답은 요청 접수만 확인합니다.",
        security = [SecurityRequirement(name = "BearerAuth")],
    )
    @ApiResponses(
        ApiResponse(responseCode = "202", description = "요청 접수 (응답 본문 없음)", content = [Content()]),
        ApiResponse(
            responseCode = "400",
            description = "잘못된 저장소 URL 또는 연결된 GitHub 계정 없음",
            content = [
                Content(mediaType = "application/json", schema = Schema(implementation = CommonApiResponse::class)),
            ],
        ),
        ApiResponse(
            responseCode = "403",
            description = "본인이 작성한 댓글이 아니며 프로젝트 수정 권한도 없음",
            content = [
                Content(mediaType = "application/json", schema = Schema(implementation = CommonApiResponse::class)),
            ],
        ),
        ApiResponse(
            responseCode = "404",
            description = "프로젝트, 등록된 저장소 또는 댓글 없음",
            content = [
                Content(mediaType = "application/json", schema = Schema(implementation = CommonApiResponse::class)),
            ],
        ),
        ApiResponse(
            responseCode = "502",
            description = "GitHub 연동 오류",
            content = [
                Content(mediaType = "application/json", schema = Schema(implementation = CommonApiResponse::class)),
            ],
        ),
    )
    @PatchMapping("/comments/{commentId}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun updateComment(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable projectId: Long,
        @PathVariable repoId: Long,
        @PathVariable prNumber: Int,
        @PathVariable commentId: Long,
        @RequestBody request: UpdateGithubCommentReqDto,
    ) = updateGithubCommentService.execute(userId, projectId, repoId, commentId, request)

    @Operation(
        summary = "PR 댓글 삭제 요청",
        description = "응답은 요청 접수만 확인합니다.",
        security = [SecurityRequirement(name = "BearerAuth")],
    )
    @ApiResponses(
        ApiResponse(responseCode = "202", description = "요청 접수 (응답 본문 없음)", content = [Content()]),
        ApiResponse(
            responseCode = "400",
            description = "등록된 GitHub 저장소 URL 형식 오류",
            content = [
                Content(mediaType = "application/json", schema = Schema(implementation = CommonApiResponse::class)),
            ],
        ),
        ApiResponse(
            responseCode = "403",
            description = "본인이 작성한 댓글이 아니며 프로젝트 수정 권한도 없음",
            content = [
                Content(mediaType = "application/json", schema = Schema(implementation = CommonApiResponse::class)),
            ],
        ),
        ApiResponse(
            responseCode = "404",
            description = "프로젝트, 등록된 저장소 또는 댓글 없음",
            content = [
                Content(mediaType = "application/json", schema = Schema(implementation = CommonApiResponse::class)),
            ],
        ),
        ApiResponse(
            responseCode = "502",
            description = "GitHub 연동 오류",
            content = [
                Content(mediaType = "application/json", schema = Schema(implementation = CommonApiResponse::class)),
            ],
        ),
    )
    @DeleteMapping("/comments/{commentId}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun deleteComment(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable projectId: Long,
        @PathVariable repoId: Long,
        @PathVariable prNumber: Int,
        @PathVariable commentId: Long,
    ) = deleteGithubCommentService.execute(userId, projectId, repoId, commentId)
}
