package com.cowork.project.domain.githubBoard.service

import com.cowork.project.domain.github.service.GithubUsernameResolver
import com.cowork.project.domain.githubBoard.event.GithubProjectItemStateEvent
import com.cowork.project.domain.githubBoard.event.GithubProjectView
import com.cowork.project.domain.githubBoard.model.GithubBoard
import com.cowork.project.domain.githubBoard.model.GithubBoardFilterEvaluator
import com.cowork.project.domain.githubBoard.model.GithubBoardFilterParser
import com.cowork.project.domain.githubBoard.model.GithubBoardItemQuery
import com.cowork.project.domain.githubBoard.model.GithubBoardSorter
import com.cowork.project.domain.githubBoard.model.ParsedGithubBoardFilter
import com.cowork.project.domain.githubBoard.presentation.data.response.GithubBoardAppliedFilterResDto
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import team.themoment.sdk.exception.ExpectedException
import java.time.LocalDate
import java.time.ZoneOffset

data class GithubBoardFilterResult(
    val items: List<GithubProjectItemStateEvent>,
    val appliedFilter: GithubBoardAppliedFilterResDto,
    val unsupportedTokens: List<String>,
)

/** 칸반 조회와 item 조회가 함께 쓰는 filter. 뷰 filter와 구조화 파라미터를 AND로 적용하고 뷰 정렬을 따른다. */
@Component
class GithubBoardItemFilter(private val usernameResolver: GithubUsernameResolver) {
    fun apply(
        board: GithubBoard,
        view: GithubProjectView,
        query: GithubBoardItemQuery,
        userId: Long,
    ): GithubBoardFilterResult {
        if (query.fields.keys.any { board.fieldByName(it) == null }) {
            throw ExpectedException("GitHub Project에 없는 field로 조회할 수 없습니다.", HttpStatus.BAD_REQUEST)
        }
        val parsed = if (query.ignoreViewFilter) {
            ParsedGithubBoardFilter(emptyList(), emptyList())
        } else {
            GithubBoardFilterParser.parse(view.filter, board)
        }
        val evaluator =
            GithubBoardFilterEvaluator(board, requesterLogin(parsed, query, userId), LocalDate.now(ZoneOffset.UTC))
        val items = board.items.filter { item ->
            parsed.conditions.all { evaluator.matches(item, it) } && evaluator.matches(item, query)
        }
        return GithubBoardFilterResult(
            items = GithubBoardSorter.sort(board, view, items),
            appliedFilter = GithubBoardAppliedFilterResDto.of(
                view.filter,
                query.ignoreViewFilter,
                parsed.conditions,
                board,
            ),
            unsupportedTokens = parsed.unsupportedTokens,
        )
    }

    // `@me`를 쓸 때만 프로필 projection을 읽는다. GitHub 계정을 연결하지 않았으면 `@me`는 아무 item과도 맞지 않는다.
    private fun requesterLogin(parsed: ParsedGithubBoardFilter, query: GithubBoardItemQuery, userId: Long): String? {
        val usesMe = query.assignees.any { it.equals("me", true) || it.equals("@me", true) } ||
            parsed.conditions.any { condition ->
                condition.qualifier == "assignee" &&
                    condition.values.any { it.equals("@me", true) }
            }
        if (!usesMe) return null
        return runCatching { usernameResolver.resolve(userId) }.getOrNull()?.takeIf { it.isNotBlank() }
    }
}
