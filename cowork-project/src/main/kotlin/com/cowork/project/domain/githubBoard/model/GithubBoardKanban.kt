package com.cowork.project.domain.githubBoard.model

import com.cowork.project.domain.githubBoard.event.GithubProjectField
import com.cowork.project.domain.githubBoard.event.GithubProjectItemStateEvent
import com.cowork.project.domain.githubBoard.event.GithubProjectView
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException

/** 칸반 컬럼 하나. `id`가 null이면 컬럼 field 값이 없는 item을 모은 컬럼이다. */
data class GithubBoardColumn(
    val id: String?,
    val name: String,
    val color: String?,
    val items: List<GithubProjectItemStateEvent>,
)

data class GithubBoardKanban(
    val columnField: GithubProjectField,
    val columns: List<GithubBoardColumn>,
    val manualOrdering: Boolean,
)

/**
 * GitHub Board 뷰와 같은 컬럼을 만든다. GitHub API는 뷰마다 저장한 컬럼 순서와 숨긴 컬럼을 주지 않으므로, 컬럼 순서는
 * single-select 옵션 순서를 따르고, iteration 컬럼은 시작일 순서를 따른다. 값이 없는 item의 컬럼은 GitHub처럼 맨 앞에 둔다.
 */
object GithubBoardKanbanBuilder {
    private val COLUMN_FIELD_TYPES = setOf("SINGLE_SELECT", "ITERATION")
    private const val DEFAULT_COLUMN_FIELD = "Status"

    fun requireBoardView(board: GithubBoard, viewNumber: Int?): GithubProjectView {
        val view = if (viewNumber == null) {
            board.views.firstOrNull { it.layout == BOARD_LAYOUT }
                ?: throw ExpectedException("GitHub Project에 Board 뷰가 없습니다.", HttpStatus.NOT_FOUND)
        } else {
            board.view(viewNumber) ?: throw ExpectedException("GitHub Project 뷰를 찾을 수 없습니다.", HttpStatus.NOT_FOUND)
        }
        if (view.layout != BOARD_LAYOUT) {
            throw ExpectedException("Board 뷰만 칸반으로 조회할 수 있습니다.", HttpStatus.BAD_REQUEST)
        }
        return view
    }

    /** Board 뷰의 컬럼 field. 뷰에 저장한 field가 없으면 GitHub 기본값인 Status field를 쓴다. */
    fun columnField(board: GithubBoard, view: GithubProjectView): GithubProjectField {
        val configured = view.verticalGroupByFieldIds.firstNotNullOfOrNull { board.field(it) }
        val field = configured ?: board.fieldByName(DEFAULT_COLUMN_FIELD)
        if (field == null || field.dataType !in COLUMN_FIELD_TYPES) {
            throw ExpectedException("Board 뷰의 컬럼 field를 찾을 수 없습니다.", HttpStatus.CONFLICT)
        }
        return field
    }

    fun columnValue(item: GithubProjectItemStateEvent, field: GithubProjectField): String? {
        val value = item.value(field.id)
        return if (field.dataType == "ITERATION") value?.iterationId else value?.optionId
    }

    /** `items`는 이미 filter를 적용하고 정렬한 목록이다. 컬럼 안 순서는 이 순서를 그대로 따른다. */
    fun build(
        board: GithubBoard,
        view: GithubProjectView,
        items: List<GithubProjectItemStateEvent>,
    ): GithubBoardKanban {
        val field = columnField(board, view)
        val byColumn = items.groupBy { item -> columnOf(item, field) }
        val emptyColumn = GithubBoardColumn(null, "No ${field.name}", null, byColumn[null].orEmpty())
        val valueColumns = if (field.dataType == "ITERATION") {
            field.iterations
                .filter { !it.completed || byColumn.containsKey(it.id) }
                .map { GithubBoardColumn(it.id, it.title, null, byColumn[it.id].orEmpty()) }
        } else {
            field.options.map { GithubBoardColumn(it.id, it.name, it.color, byColumn[it.id].orEmpty()) }
        }
        return GithubBoardKanban(field, listOf(emptyColumn) + valueColumns, view.sortBy.isEmpty())
    }

    fun isColumn(field: GithubProjectField, columnId: String): Boolean = if (field.dataType == "ITERATION") {
        field.iterations.any { it.id == columnId }
    } else {
        field.options.any { it.id == columnId }
    }

    /** item이 들어 있는 컬럼 ID. 컬럼 field 값이 없거나 지워진 옵션이면 값이 없는 컬럼을 뜻하는 null이다. */
    fun columnOf(item: GithubProjectItemStateEvent, field: GithubProjectField): String? =
        columnValue(item, field)?.takeIf { isColumn(field, it) }

    /** 컬럼 안 item ID를 GitHub 위치 순서로 돌려준다. */
    fun columnItemIds(board: GithubBoard, field: GithubProjectField, columnId: String?): List<String> =
        board.items.filter { columnOf(it, field) == columnId }.map { it.itemId }
}

/**
 * 컬럼 안의 위치를 GitHub의 Project 전체 위치로 바꾼다. GitHub는 위치를 Project 전체에서 하나만 가지므로,
 * "컬럼 맨 위"는 그 컬럼 첫 카드의 바로 앞 카드 뒤를 뜻한다.
 */
object GithubBoardPositionResolver {
    /**
     * [movingItemId]를 [columnItemIds] 안에서 [afterItemId] 뒤에 두기 위한 GitHub `afterId`를 구한다.
     * 컬럼에 다른 카드가 없으면 위치를 바꿀 필요가 없어 [Placement.Unchanged]를 돌려준다.
     */
    fun resolve(
        globalOrder: List<String>,
        columnItemIds: List<String>,
        movingItemId: String,
        afterItemId: String?,
    ): Placement {
        val others = columnItemIds.filter { it != movingItemId }
        if (afterItemId != null) {
            if (afterItemId == movingItemId || afterItemId !in others) {
                throw ExpectedException("기준 카드가 같은 컬럼에 없습니다.", HttpStatus.BAD_REQUEST)
            }
            return Placement.After(afterItemId)
        }
        val first = others.firstOrNull() ?: return Placement.Unchanged
        val order = globalOrder.filter { it != movingItemId }
        val index = order.indexOf(first)
        return Placement.After(if (index <= 0) null else order[index - 1])
    }

    sealed interface Placement {
        data object Unchanged : Placement

        /** `afterId`가 null이면 Project 전체의 맨 앞이다. */
        data class After(val afterId: String?) : Placement
    }
}
