package com.cowork.project.domain.githubBoard.model

import com.cowork.project.domain.githubBoard.event.GithubProjectField
import com.cowork.project.domain.githubBoard.event.GithubProjectItemStateEvent
import com.cowork.project.domain.githubBoard.event.GithubProjectView
import java.util.Locale

/** 뷰 그룹 하나. `id`가 null이면 그룹 field 값이 없는 item을 모은 그룹이거나, 그룹 기준이 없을 때 모든 item을 담은 그룹이다. */
data class GithubBoardGroup(
    val id: String?,
    val name: String,
    val color: String?,
    val items: List<GithubProjectItemStateEvent>,
)

/**
 * 뷰의 그룹 결과. `field`는 뷰에 저장한 그룹 field이며 없으면 null이다.
 * `supported`가 false이면 담당자나 라벨처럼 값이 여러 개인 field라서 모든 item을 그룹 하나에 담았다.
 */
data class GithubBoardGrouping(
    val field: GithubProjectField?,
    val supported: Boolean,
    val groups: List<GithubBoardGroup>,
)

/**
 * Table 뷰와 Roadmap 뷰가 함께 쓰는 그룹 규칙. 칸반 컬럼과 같은 순서를 따른다. 값이 없는 item의 그룹을 맨 앞에 두고,
 * single-select는 옵션 순서, iteration은 시작일 순서이며 카드가 없는 지난 iteration은 숨긴다. 그 밖의 field는 값의 오름차순이다.
 * 그룹 안 순서는 입력 순서를 그대로 따른다.
 */
object GithubBoardGrouper {
    val GROUP_FIELD_TYPES = setOf("SINGLE_SELECT", "ITERATION", "TEXT", "NUMBER", "DATE", "REPOSITORY", "MILESTONE")
    const val ALL_ITEMS_GROUP_NAME = "All items"

    /** 뷰에 저장한 그룹 field. 저장한 field가 Project에서 지워졌으면 그룹 기준이 없는 것으로 본다. */
    fun groupField(board: GithubBoard, view: GithubProjectView): GithubProjectField? =
        view.groupByFieldIds.firstNotNullOfOrNull { board.field(it) }

    /** `items`는 이미 filter를 적용하고 정렬한 목록이다. */
    fun group(
        board: GithubBoard,
        view: GithubProjectView,
        items: List<GithubProjectItemStateEvent>,
    ): GithubBoardGrouping {
        val field = groupField(board, view)
        if (field == null || field.dataType !in GROUP_FIELD_TYPES) {
            val all = GithubBoardGroup(null, ALL_ITEMS_GROUP_NAME, null, items)
            return GithubBoardGrouping(field, field == null, listOf(all))
        }
        val byGroup = items.groupBy { groupOf(it, field) }
        val emptyGroup = GithubBoardGroup(null, "No ${field.name}", null, byGroup[null].orEmpty())
        return GithubBoardGrouping(field, true, listOf(emptyGroup) + valueGroups(field, byGroup))
    }

    private fun valueGroups(
        field: GithubProjectField,
        byGroup: Map<String?, List<GithubProjectItemStateEvent>>,
    ): List<GithubBoardGroup> = when (field.dataType) {
        "SINGLE_SELECT" -> field.options.map { GithubBoardGroup(it.id, it.name, it.color, byGroup[it.id].orEmpty()) }

        "ITERATION" ->
            field.iterations
                .filter { !it.completed || byGroup.containsKey(it.id) }
                .map { GithubBoardGroup(it.id, it.title, null, byGroup[it.id].orEmpty()) }

        else -> byGroup.keys.filterNotNull()
            .sortedWith(valueOrder(field))
            .map { GithubBoardGroup(it, displayName(field, it), null, byGroup.getValue(it)) }
    }

    private fun valueOrder(field: GithubProjectField): Comparator<String> = when (field.dataType) {
        "NUMBER" -> compareBy { it.toDouble() }
        "DATE" -> naturalOrder()
        else -> compareBy<String> { it.lowercase(Locale.ROOT) }.thenBy { it }
    }

    private fun displayName(field: GithubProjectField, key: String): String = if (field.dataType == "NUMBER") {
        key.toDouble().let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() }
    } else {
        key
    }

    /** item이 들어가는 그룹 ID. 값이 없거나 지워진 옵션, iteration이면 값이 없는 그룹을 뜻하는 null이다. */
    fun groupOf(item: GithubProjectItemStateEvent, field: GithubProjectField): String? {
        val value = item.value(field.id)
        return when (field.dataType) {
            "SINGLE_SELECT" -> value?.optionId?.takeIf { id -> field.options.any { it.id == id } }
            "ITERATION" -> value?.iterationId?.takeIf { id -> field.iterations.any { it.id == id } }
            "TEXT" -> value?.text
            "NUMBER" -> value?.number?.toString()
            "DATE" -> value?.date
            "REPOSITORY" -> item.content?.repository
            "MILESTONE" -> item.content?.milestone
            else -> null
        }?.takeIf { it.isNotEmpty() }
    }
}
