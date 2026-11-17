package com.cowork.project.domain.githubBoard.model

import com.cowork.project.domain.githubBoard.event.GithubProjectField
import com.cowork.project.domain.githubBoard.event.GithubProjectItemStateEvent
import com.cowork.project.domain.githubBoard.event.GithubProjectView

/**
 * 뷰에 저장한 정렬을 적용한다. 정렬이 없으면 GitHub 위치 순서를 그대로 둔다. 담당자나 라벨처럼 비교할 수 없는 field는 건너뛰며,
 * 같은 값끼리는 위치 순서를 유지한다. 값이 없는 item은 방향과 상관없이 뒤에 둔다.
 */
object GithubBoardSorter {
    fun sort(
        board: GithubBoard,
        view: GithubProjectView,
        items: List<GithubProjectItemStateEvent>,
    ): List<GithubProjectItemStateEvent> {
        val comparators = view.sortBy.mapNotNull { sort ->
            val field = board.field(sort.fieldId) ?: return@mapNotNull null
            val key = sortKey(field) ?: return@mapNotNull null
            val descending = sort.direction.equals("DESC", ignoreCase = true)
            Comparator<GithubProjectItemStateEvent> { left, right ->
                val leftKey = key(left)
                val rightKey = key(right)
                when {
                    leftKey == null && rightKey == null -> 0
                    leftKey == null -> 1
                    rightKey == null -> -1
                    descending -> rightKey.compareTo(leftKey)
                    else -> leftKey.compareTo(rightKey)
                }
            }
        }
        if (comparators.isEmpty()) return items
        return items.sortedWith(comparators.reduce { first, second -> first.then(second) })
    }

    private fun sortKey(field: GithubProjectField): ((GithubProjectItemStateEvent) -> Comparable<Any>?)? {
        @Suppress("UNCHECKED_CAST")
        fun <T : Comparable<T>> key(extract: (GithubProjectItemStateEvent) -> T?) =
            extract as (GithubProjectItemStateEvent) -> Comparable<Any>?
        return when (field.dataType) {
            "TITLE" -> key { item -> item.title().lowercase() }

            "TEXT" -> key { item -> item.value(field.id)?.text?.lowercase() }

            "NUMBER" -> key { item -> item.value(field.id)?.number }

            "DATE" -> key { item -> item.value(field.id)?.date }

            "SINGLE_SELECT" -> key { item ->
                field.options.indexOfFirst { it.id == item.value(field.id)?.optionId }.takeIf { it >= 0 }
            }

            "ITERATION" -> key { item ->
                field.iterations.firstOrNull { it.id == item.value(field.id)?.iterationId }?.startDate
            }

            else -> null
        }
    }
}
