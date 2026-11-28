package com.cowork.project.domain.github.presentation.data.response

import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.util.UUID

data class GithubTimelineResDto(
    @field:Schema(description = "발생 시각 오름차순 활동 목록")
    val items: List<GithubTimelineActivityResDto>,
    @field:Schema(description = "조회한 스냅샷 ID. 다음 페이지 요청에 사용합니다.")
    val snapshotId: UUID,
    @field:Schema(description = "GitHub에서 이력을 마지막으로 동기화한 시각")
    val syncedAt: Instant,
    @field:Schema(description = "다음 페이지 번호. 마지막 페이지이면 null입니다.")
    val nextPage: Int?,
)

data class GithubTimelineActivityResDto(
    @field:Schema(description = "활동 식별자")
    val id: String,
    @field:Schema(
        description = "GitHub 활동 유형",
        allowableValues = [
            "commented",
            "committed",
            "referenced",
            "reviewed",
            "review_commented",
            "review_dismissed",
            "labeled",
            "unlabeled",
            "closed",
            "reopened",
            "merged",
        ],
    )
    val type: String,
    @field:Schema(description = "활동 발생 시각. 커밋은 커미터 시각, 리뷰는 제출 시각입니다.")
    val occurredAt: Instant,
    @field:Schema(description = "활동 작성자")
    val actor: String? = null,
    @field:Schema(description = "댓글 본문, 커밋 메시지 또는 리뷰 내용")
    val body: String? = null,
    @field:Schema(description = "GitHub 활동 링크")
    val htmlUrl: String? = null,
    @field:Schema(description = "연관 커밋 SHA")
    val commitSha: String? = null,
    @field:Schema(description = "GitHub 리뷰 상태")
    val reviewState: String? = null,
    @field:Schema(description = "추가하거나 제거한 라벨 이름")
    val labelName: String? = null,
    @field:Schema(description = "라벨 색상")
    val labelColor: String? = null,
    @field:Schema(description = "GitHub 댓글 ID")
    val commentId: Long? = null,
    @field:Schema(description = "GitHub 리뷰 ID")
    val reviewId: Long? = null,
    @field:Schema(description = "리뷰 댓글의 파일 경로")
    val path: String? = null,
    @field:Schema(description = "리뷰 댓글의 줄 번호. 변경된 줄은 원래 줄 번호입니다.")
    val line: Int? = null,
)
