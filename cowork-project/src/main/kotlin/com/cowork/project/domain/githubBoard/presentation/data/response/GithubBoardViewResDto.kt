package com.cowork.project.domain.githubBoard.presentation.data.response

import com.cowork.project.domain.githubBoard.event.GithubProjectView
import io.swagger.v3.oas.annotations.media.Schema

data class GithubBoardViewResDto(
    @field:Schema(description = "뷰 번호(viewNumber)", example = "1")
    val number: Int,
    @field:Schema(description = "뷰 이름", example = "Board")
    val name: String,
    @field:Schema(
        description = "레이아웃",
        example = "BOARD_LAYOUT",
        allowableValues = ["BOARD_LAYOUT", "TABLE_LAYOUT", "ROADMAP_LAYOUT"],
    )
    val layout: String,
    @field:Schema(description = "뷰에 저장된 GitHub filter 문자열", example = "is:open assignee:@me", nullable = true)
    val filter: String?,
    @field:Schema(description = "뷰 정렬이 없어 카드 순서를 직접 바꿀 수 있는지 여부", example = "true")
    val manualOrdering: Boolean,
) {
    companion object {
        fun of(view: GithubProjectView) = GithubBoardViewResDto(
            number = view.number,
            name = view.name,
            layout = view.layout,
            filter = view.filter,
            manualOrdering = view.sortBy.isEmpty(),
        )
    }
}
