package com.cowork.project.domain.githubBoard.event

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.time.Instant

const val GITHUB_PROJECT_STATE_TOPIC = "github.project.state.v1"
const val GITHUB_PROJECT_ITEM_STATE_TOPIC = "github.project-item.state.v1"
const val GITHUB_PROJECT_ITEM_WRITE_COMMAND_TOPIC = "github.project-item-write.command.v1"
const val GITHUB_PROJECT_ITEM_WRITE_RESULT_TOPIC = "github.project-item-write.result.v1"

@JsonIgnoreProperties(ignoreUnknown = true)
data class GithubProjectFieldOption(
    val id: String = "",
    val name: String = "",
    val color: String = "",
    val description: String = "",
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class GithubProjectIteration(
    val id: String = "",
    val title: String = "",
    val startDate: String = "",
    val duration: Int = 0,
    val completed: Boolean = false,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class GithubProjectField(
    val id: String = "",
    val name: String = "",
    val dataType: String = "",
    val options: List<GithubProjectFieldOption> = emptyList(),
    val iterations: List<GithubProjectIteration> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class GithubProjectViewSort(val fieldId: String = "", val direction: String = "")

@JsonIgnoreProperties(ignoreUnknown = true)
data class GithubProjectView(
    val id: String = "",
    val number: Int = 0,
    val name: String = "",
    val layout: String = "",
    val filter: String? = null,
    val visibleFieldIds: List<String> = emptyList(),
    val groupByFieldIds: List<String> = emptyList(),
    val verticalGroupByFieldIds: List<String> = emptyList(),
    val sortBy: List<GithubProjectViewSort> = emptyList(),
)

/**
 * `cowork-github`가 발행하는 GitHub Project 설정과 item 순서. `itemIds`에 있는 item만 보드에 보이며, 이 순서가 GitHub의 위치 순서다.
 * `accessError`가 있으면 Project를 찾을 수 없거나 앱에 Projects 권한이 없어 나머지 필드는 비어 있다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class GithubProjectStateEvent(
    val owner: String = "",
    val projectNumber: Int = 0,
    val nodeId: String? = null,
    val title: String = "",
    val url: String = "",
    val closed: Boolean = false,
    val fields: List<GithubProjectField> = emptyList(),
    val views: List<GithubProjectView> = emptyList(),
    val itemIds: List<String> = emptyList(),
    val accessError: String? = null,
    val deleted: Boolean = false,
    val sourceOccurredAt: Instant = Instant.EPOCH,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class GithubProjectItemFieldValue(
    val fieldId: String = "",
    val fieldName: String = "",
    val dataType: String = "",
    val text: String? = null,
    val number: Double? = null,
    val date: String? = null,
    val optionId: String? = null,
    val iterationId: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class GithubProjectItemLabel(val name: String = "", val color: String = "")

@JsonIgnoreProperties(ignoreUnknown = true)
data class GithubProjectItemContent(
    val title: String = "",
    val body: String? = null,
    val number: Int? = null,
    val state: String? = null,
    val url: String? = null,
    val repository: String? = null,
    val assignees: List<String> = emptyList(),
    val labels: List<GithubProjectItemLabel> = emptyList(),
    val milestone: String? = null,
    val draft: Boolean = false,
)

/** GitHub Project item 하나의 상태. 삭제 웹훅으로 만든 레코드에는 `projectNumber`와 content가 없다. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class GithubProjectItemStateEvent(
    val owner: String = "",
    val projectNodeId: String = "",
    val projectNumber: Int? = null,
    val itemId: String = "",
    val type: String = "",
    val contentNodeId: String? = null,
    val content: GithubProjectItemContent? = null,
    val fieldValues: List<GithubProjectItemFieldValue> = emptyList(),
    val archived: Boolean = false,
    val deleted: Boolean = false,
    val sourceOccurredAt: Instant = Instant.EPOCH,
)

enum class GithubProjectItemWriteCommandType {
    MOVE_ITEM,
    REORDER_ITEM,
    SET_FIELD,
    CLEAR_FIELD,
    CREATE_DRAFT,
    UPDATE_DRAFT,
    ADD_ITEM,
    REMOVE_ITEM,
    CONVERT_DRAFT,
    SET_ASSIGNEES,
}

data class GithubSetFieldPayload(val fieldId: String, val value: Map<String, Any>)
data class GithubClearFieldPayload(val fieldId: String)
data class GithubDraftPayload(val title: String?, val body: String?)
data class GithubAddItemPayload(val repo: String, val number: Int)
data class GithubConvertDraftPayload(val repo: String)
data class GithubSetAssigneesPayload(val repo: String, val assignees: List<String>)

/** 카드를 다른 컬럼으로 옮긴다. 두 값이 모두 null이면 컬럼 field 값을 지운다. `reposition`이 true이면 `afterItemId` 뒤로도 옮긴다. */
data class GithubMoveItemPayload(
    val fieldId: String,
    val singleSelectOptionId: String?,
    val iterationId: String?,
    val reposition: Boolean,
    val afterItemId: String?,
)

/** 카드 위치를 `afterItemId` 바로 뒤로 옮긴다. null이면 Project 전체의 맨 앞이다. */
data class GithubReorderItemPayload(val afterItemId: String?)

/**
 * Project item 쓰기 커맨드. Kafka 메시지 키는 Project node ID다. 같은 Project의 쓰기가 발행 순서대로 실행되게 하기 위해서다.
 * 카드 생성처럼 아직 item이 없는 커맨드도 같은 봉투를 쓰도록 `itemId`는 null일 수 있다.
 */
data class GithubProjectItemWriteCommand(
    val schemaVersion: Int = 1,
    val operationId: String,
    val idempotencyKey: String,
    val commandType: GithubProjectItemWriteCommandType,
    val owner: String,
    val projectNodeId: String,
    val itemId: String?,
    val requestedBy: Long,
    val occurredAt: Instant,
    val payload: Any,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class GithubProjectItemWriteError(val code: String = "", val message: String = "")

@JsonIgnoreProperties(ignoreUnknown = true)
data class GithubProjectItemWriteResult(
    val schemaVersion: Int = 0,
    val operationId: String = "",
    val idempotencyKey: String = "",
    val commandType: String = "",
    val status: String = "",
    val result: Map<String, Any?>? = null,
    val error: GithubProjectItemWriteError? = null,
    val occurredAt: Instant? = null,
)
