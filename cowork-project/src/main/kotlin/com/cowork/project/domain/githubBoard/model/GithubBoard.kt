package com.cowork.project.domain.githubBoard.model

import com.cowork.project.domain.githubBoard.event.GithubProjectField
import com.cowork.project.domain.githubBoard.event.GithubProjectItemFieldValue
import com.cowork.project.domain.githubBoard.event.GithubProjectItemStateEvent
import com.cowork.project.domain.githubBoard.event.GithubProjectView
import java.util.Locale

const val BOARD_LAYOUT = "BOARD_LAYOUT"
const val TABLE_LAYOUT = "TABLE_LAYOUT"

/** 조회 한 번에 쓰는 GitHub Project 상태. `items`는 GitHub의 위치 순서이며 보관된 item과 삭제된 item은 없다. */
data class GithubBoard(
    val projectNodeId: String,
    val fields: List<GithubProjectField>,
    val views: List<GithubProjectView>,
    val items: List<GithubProjectItemStateEvent>,
) {
    fun view(number: Int): GithubProjectView? = views.firstOrNull { it.number == number }

    fun field(id: String): GithubProjectField? = fields.firstOrNull { it.id == id }

    /** 이름이 같은 field를 찾는다. GitHub filter는 공백을 `-`로 바꾼 이름도 쓰므로 둘 다 받는다. */
    fun fieldByName(name: String): GithubProjectField? {
        val key = name.filterKey()
        return fields.firstOrNull { it.name.filterKey() == key }
    }

    fun item(id: String): GithubProjectItemStateEvent? = items.firstOrNull { it.itemId == id }
}

fun String.filterKey(): String = trim().lowercase(Locale.ROOT).replace(' ', '-')

fun GithubProjectItemStateEvent.value(fieldId: String): GithubProjectItemFieldValue? =
    fieldValues.firstOrNull { it.fieldId == fieldId }
