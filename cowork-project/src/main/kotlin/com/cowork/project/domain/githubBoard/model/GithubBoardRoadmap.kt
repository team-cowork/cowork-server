package com.cowork.project.domain.githubBoard.model

import com.cowork.project.domain.githubBoard.event.GithubProjectField
import com.cowork.project.domain.githubBoard.event.GithubProjectItemStateEvent
import com.cowork.project.domain.githubBoard.event.GithubProjectView
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException
import java.time.LocalDate
import java.time.format.DateTimeParseException

/** Roadmap 타임라인의 날짜 단위. GitHub API가 뷰의 확대 단위를 주지 않아 지금은 [MONTH]만 쓴다. */
enum class GithubBoardRoadmapDateUnit { MONTH, }

/** 시작 field와 종료 field를 정한 곳. [REQUEST]는 요청 파라미터, [DEFAULT]는 서버 기본 규칙이다. */
enum class GithubBoardRoadmapSettingsSource { REQUEST, DEFAULT }

/** 카드가 타임라인에서 차지하는 구간. 두 날짜를 모두 포함한다. */
data class GithubBoardSpan(val start: LocalDate, val end: LocalDate)

data class GithubBoardRoadmapItem(val item: GithubProjectItemStateEvent, val span: GithubBoardSpan)

data class GithubBoardRoadmapGroup(
    val id: String?,
    val name: String,
    val color: String?,
    val items: List<GithubBoardRoadmapItem>,
)

data class GithubBoardRoadmapFields(
    val start: GithubProjectField,
    val end: GithubProjectField,
    val source: GithubBoardRoadmapSettingsSource,
)

/** `groups`에는 구간이 있는 카드만 있다. 시작 field와 종료 field가 모두 비어 있는 카드는 `unscheduled`에 있다. */
data class GithubBoardRoadmap(
    val fields: GithubBoardRoadmapFields,
    val dateUnit: GithubBoardRoadmapDateUnit,
    val groupField: GithubProjectField?,
    val groupSupported: Boolean,
    val groups: List<GithubBoardRoadmapGroup>,
    val unscheduled: List<GithubProjectItemStateEvent>,
)

/**
 * GitHub Roadmap 뷰와 같은 타임라인을 만든다.
 *
 * GitHub GraphQL API의 `ProjectV2View`는 Roadmap 뷰에 저장한 시작 field, 종료 field, 확대 단위를 주지 않는다.
 * 그래서 시작 field와 종료 field는 [selectFields]의 기본 규칙으로 정하고 요청 파라미터로 바꿀 수 있게 하며,
 * 날짜 단위는 [GithubBoardRoadmapDateUnit.MONTH]로 둔다. 그룹은 뷰에 저장한 그룹 field로 [GithubBoardGrouper]가 나눈다.
 */
object GithubBoardRoadmapBuilder {
    private val DATE_FIELD_TYPES = setOf("DATE", "ITERATION")
    private val START_FIELD_NAMES = listOf("Start", "Start date")
    private val END_FIELD_NAMES = listOf("Target date", "End date", "Due", "Due date")

    fun requireRoadmapView(board: GithubBoard, viewNumber: Int): GithubProjectView {
        val view =
            board.view(viewNumber) ?: throw ExpectedException("GitHub Project 뷰를 찾을 수 없습니다.", HttpStatus.NOT_FOUND)
        if (view.layout != ROADMAP_LAYOUT) {
            throw ExpectedException("Roadmap 뷰만 로드맵으로 조회할 수 있습니다.", HttpStatus.BAD_REQUEST)
        }
        return view
    }

    /**
     * 시작 field와 종료 field를 정한다. 요청한 field ID가 있으면 그 field를 쓰고, 한쪽만 요청하면 다른 쪽은 기본 규칙을 따른다.
     *
     * 기본 규칙은 이 순서다. 이름이 Start, Start date인 date field를 시작 field로, Target date, End date, Due, Due date인
     * date field를 종료 field로 쓴다. 한쪽만 있으면 그 field를 양쪽에 쓴다. 이런 field가 없으면 첫 iteration field를,
     * 그것도 없으면 첫 date field를 양쪽에 쓴다.
     */
    fun selectFields(board: GithubBoard, startFieldId: String?, endFieldId: String?): GithubBoardRoadmapFields {
        val requestedStart = startFieldId?.let { requireDateField(board, it) }
        val requestedEnd = endFieldId?.let { requireDateField(board, it) }
        val default = if (requestedStart == null || requestedEnd == null) defaultFields(board) else null
        val start = requestedStart ?: default?.first ?: requestedEnd
        val end = requestedEnd ?: default?.second ?: requestedStart
        if (start == null || end == null) {
            throw ExpectedException("Roadmap 뷰의 날짜 field를 찾을 수 없습니다.", HttpStatus.CONFLICT)
        }
        val source = if (requestedStart != null || requestedEnd != null) {
            GithubBoardRoadmapSettingsSource.REQUEST
        } else {
            GithubBoardRoadmapSettingsSource.DEFAULT
        }
        return GithubBoardRoadmapFields(start, end, source)
    }

    private fun requireDateField(board: GithubBoard, fieldId: String): GithubProjectField =
        board.field(fieldId)?.takeIf { it.dataType in DATE_FIELD_TYPES }
            ?: throw ExpectedException("시작 field와 종료 field는 date 또는 iteration field여야 합니다.", HttpStatus.BAD_REQUEST)

    private fun defaultFields(board: GithubBoard): Pair<GithubProjectField, GithubProjectField>? {
        val namedStart = namedDateField(board, START_FIELD_NAMES)
        val namedEnd = namedDateField(board, END_FIELD_NAMES)
        val start = namedStart ?: namedEnd
        val end = namedEnd ?: namedStart
        if (start != null && end != null) return start to end
        val fallback = board.fields.firstOrNull { it.dataType == "ITERATION" }
            ?: board.fields.firstOrNull { it.dataType == "DATE" }
            ?: return null
        return fallback to fallback
    }

    private fun namedDateField(board: GithubBoard, names: List<String>): GithubProjectField? =
        names.firstNotNullOfOrNull { name -> board.fieldByName(name)?.takeIf { it.dataType == "DATE" } }

    /** `items`는 이미 filter를 적용하고 정렬한 목록이다. 그룹 안 순서와 `unscheduled` 순서는 이 순서를 따른다. */
    fun build(
        board: GithubBoard,
        view: GithubProjectView,
        items: List<GithubProjectItemStateEvent>,
        fields: GithubBoardRoadmapFields,
    ): GithubBoardRoadmap {
        val scheduled = items
            .mapNotNull { item ->
                span(item, fields.start, fields.end)?.let {
                    item.itemId to
                        GithubBoardRoadmapItem(item, it)
                }
            }
            .toMap()
        val (scheduledItems, unscheduled) = items.partition { it.itemId in scheduled }
        val grouping = GithubBoardGrouper.group(board, view, scheduledItems)
        return GithubBoardRoadmap(
            fields = fields,
            dateUnit = GithubBoardRoadmapDateUnit.MONTH,
            groupField = grouping.field,
            groupSupported = grouping.supported,
            groups = grouping.groups.map { group ->
                GithubBoardRoadmapGroup(
                    group.id,
                    group.name,
                    group.color,
                    group.items.map { scheduled.getValue(it.itemId) },
                )
            },
            unscheduled = unscheduled,
        )
    }

    /**
     * 카드의 구간. 시작은 시작 field 값의 시작일이고 끝은 종료 field 값의 종료일이다. 두 날짜가 모두 없으면 null이다.
     * 한쪽만 있으면 그 날짜 하루이고, 끝이 시작보다 앞이면 시작일 하루다.
     */
    fun span(
        item: GithubProjectItemStateEvent,
        startField: GithubProjectField,
        endField: GithubProjectField,
    ): GithubBoardSpan? {
        val start = startDate(item, startField)
        val end = endDate(item, endField)
        if (start == null) return end?.let { GithubBoardSpan(it, it) }
        if (end == null || end.isBefore(start)) return GithubBoardSpan(start, start)
        return GithubBoardSpan(start, end)
    }

    private fun startDate(item: GithubProjectItemStateEvent, field: GithubProjectField): LocalDate? =
        if (field.dataType == "ITERATION") {
            iterationRange(item, field)?.first
        } else {
            parseDate(item.value(field.id)?.date)
        }

    private fun endDate(item: GithubProjectItemStateEvent, field: GithubProjectField): LocalDate? =
        if (field.dataType == "ITERATION") {
            iterationRange(item, field)?.second
        } else {
            parseDate(item.value(field.id)?.date)
        }

    /** iteration의 시작일과 종료일. 종료일은 시작일에 기간을 더한 날의 하루 전이다. 지워진 iteration이면 null이다. */
    fun iterationRange(item: GithubProjectItemStateEvent, field: GithubProjectField): Pair<LocalDate, LocalDate>? {
        val iterationId = item.value(field.id)?.iterationId ?: return null
        val iteration = field.iterations.firstOrNull { it.id == iterationId } ?: return null
        val start = parseDate(iteration.startDate) ?: return null
        return start to start.plusDays(maxOf(iteration.duration, 1) - 1L)
    }

    private fun parseDate(value: String?): LocalDate? = value?.let {
        try {
            LocalDate.parse(it)
        } catch (_: DateTimeParseException) {
            null
        }
    }
}
