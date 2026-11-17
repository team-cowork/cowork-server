package com.cowork.project.domain.githubBoard

import com.cowork.project.domain.githubBoard.event.GithubProjectField
import com.cowork.project.domain.githubBoard.event.GithubProjectFieldOption
import com.cowork.project.domain.githubBoard.event.GithubProjectItemContent
import com.cowork.project.domain.githubBoard.event.GithubProjectItemFieldValue
import com.cowork.project.domain.githubBoard.event.GithubProjectItemLabel
import com.cowork.project.domain.githubBoard.event.GithubProjectItemStateEvent
import com.cowork.project.domain.githubBoard.event.GithubProjectIteration
import com.cowork.project.domain.githubBoard.event.GithubProjectView
import com.cowork.project.domain.githubBoard.event.GithubProjectViewSort
import com.cowork.project.domain.githubBoard.model.GithubBoard

object GithubBoardFixtures {
    val status = GithubProjectField(
        id = "F_STATUS",
        name = "Status",
        dataType = "SINGLE_SELECT",
        options = listOf(
            GithubProjectFieldOption("opt-todo", "Todo", "GRAY"),
            GithubProjectFieldOption("opt-doing", "In Progress", "YELLOW"),
            GithubProjectFieldOption("opt-done", "Done", "GREEN"),
        ),
    )
    val sprint = GithubProjectField(
        id = "F_SPRINT",
        name = "Sprint",
        dataType = "ITERATION",
        iterations = listOf(
            GithubProjectIteration("it-1", "Sprint 1", "2026-11-01", 7, completed = true),
            GithubProjectIteration("it-2", "Sprint 2", "2026-11-08", 7, completed = true),
            GithubProjectIteration("it-3", "Sprint 3", "2026-11-15", 7),
            GithubProjectIteration("it-4", "Sprint 4", "2026-11-22", 7),
        ),
    )
    val points = GithubProjectField(id = "F_POINTS", name = "Story Points", dataType = "NUMBER")
    val due = GithubProjectField(id = "F_DUE", name = "Due", dataType = "DATE")
    val issueType = GithubProjectField(id = "F_TYPE", name = "Type", dataType = "ISSUE_TYPE")

    fun view(
        number: Int = 1,
        layout: String = "BOARD_LAYOUT",
        filter: String? = null,
        columnFieldIds: List<String> = listOf(status.id),
        sortBy: List<GithubProjectViewSort> = emptyList(),
    ) = GithubProjectView(
        id = "PVTV_$number",
        number = number,
        name = "View $number",
        layout = layout,
        filter = filter,
        verticalGroupByFieldIds = columnFieldIds,
        sortBy = sortBy,
    )

    fun item(
        id: String,
        type: String = "ISSUE",
        title: String = "item $id",
        state: String = "OPEN",
        repository: String? = "my-org/app",
        assignees: List<String> = emptyList(),
        labels: List<String> = emptyList(),
        statusOption: String? = null,
        iteration: String? = null,
        points: Double? = null,
        due: String? = null,
    ) = GithubProjectItemStateEvent(
        owner = "my-org",
        projectNodeId = "PVT_1",
        projectNumber = 3,
        itemId = id,
        type = type,
        content = GithubProjectItemContent(
            title = title,
            number = 1,
            state = state,
            repository = repository,
            assignees = assignees,
            labels = labels.map { GithubProjectItemLabel(it, "000000") },
            draft = type == "DRAFT_ISSUE",
        ),
        fieldValues = listOfNotNull(
            statusOption?.let { GithubProjectItemFieldValue("F_STATUS", "Status", "SINGLE_SELECT", optionId = it) },
            iteration?.let { GithubProjectItemFieldValue("F_SPRINT", "Sprint", "ITERATION", iterationId = it) },
            points?.let { GithubProjectItemFieldValue("F_POINTS", "Story Points", "NUMBER", number = it) },
            due?.let { GithubProjectItemFieldValue("F_DUE", "Due", "DATE", date = it) },
        ),
    )

    fun board(items: List<GithubProjectItemStateEvent>, views: List<GithubProjectView> = listOf(view())) = GithubBoard(
        projectNodeId = "PVT_1",
        fields = listOf(status, sprint, points, due, issueType),
        views = views,
        items = items,
    )
}
