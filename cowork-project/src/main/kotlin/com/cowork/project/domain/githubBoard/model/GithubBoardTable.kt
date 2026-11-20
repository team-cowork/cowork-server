package com.cowork.project.domain.githubBoard.model

import com.cowork.project.domain.githubBoard.event.GithubProjectField
import com.cowork.project.domain.githubBoard.event.GithubProjectItemStateEvent
import com.cowork.project.domain.githubBoard.event.GithubProjectView
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException

data class GithubBoardTableSort(val field: GithubProjectField, val direction: String)

data class GithubBoardTable(
    val visibleFields: List<GithubProjectField>,
    val sortBy: List<GithubBoardTableSort>,
    val grouping: GithubBoardGrouping,
)

/** GitHub Table 뷰에 저장한 표시 field, 정렬, 그룹을 만든다. 행 순서는 [GithubBoardSorter]가 정한 순서를 따른다. */
object GithubBoardTableBuilder {
    fun requireTableView(board: GithubBoard, viewNumber: Int): GithubProjectView {
        val view =
            board.view(viewNumber) ?: throw ExpectedException("GitHub Project 뷰를 찾을 수 없습니다.", HttpStatus.NOT_FOUND)
        if (view.layout != TABLE_LAYOUT) {
            throw ExpectedException("Table 뷰만 테이블로 조회할 수 있습니다.", HttpStatus.BAD_REQUEST)
        }
        return view
    }

    /**
     * `items`는 이미 filter를 적용하고 정렬한 목록이다. 표시 field는 뷰에 저장한 열 순서이며, Project에서 지워진 field는 뺀다.
     * 정렬은 뷰에 저장한 값을 그대로 돌려준다.
     */
    fun build(board: GithubBoard, view: GithubProjectView, items: List<GithubProjectItemStateEvent>): GithubBoardTable =
        GithubBoardTable(
            visibleFields = view.visibleFieldIds.mapNotNull { board.field(it) },
            sortBy = view.sortBy.mapNotNull { sort ->
                board.field(sort.fieldId)?.let { GithubBoardTableSort(it, sort.direction) }
            },
            grouping = GithubBoardGrouper.group(board, view, items),
        )
}
