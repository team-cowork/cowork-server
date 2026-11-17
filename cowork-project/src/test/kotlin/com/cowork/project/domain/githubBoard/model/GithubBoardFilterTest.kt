package com.cowork.project.domain.githubBoard.model

import com.cowork.project.domain.githubBoard.GithubBoardFixtures.board
import com.cowork.project.domain.githubBoard.GithubBoardFixtures.item
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.time.LocalDate

class GithubBoardFilterTest :
    DescribeSpec({
        val today = LocalDate.parse("2026-11-17")
        val items = listOf(
            item(
                "a",
                assignees = listOf("octocat"),
                labels = listOf("bug"),
                statusOption = "opt-todo",
                points = 3.0,
                due = "2026-11-17",
            ),
            item(
                "b",
                type = "PULL_REQUEST",
                state = "MERGED",
                labels = listOf("feature-ui"),
                statusOption = "opt-done",
                iteration = "it-3",
            ),
            item(
                "c",
                type = "DRAFT_ISSUE",
                title = "로그인 오류 수정",
                repository = null,
                statusOption = "opt-doing",
                points = 8.0,
            ),
            item(
                "d",
                state = "CLOSED",
                repository = "my-org/api",
                assignees = listOf("hubot"),
                iteration = "it-4",
                due = "2026-12-01",
            ),
        )
        val board = board(items)

        fun matching(filter: String, login: String? = "octocat"): List<String> {
            val parsed = GithubBoardFilterParser.parse(filter, board)
            val evaluator = GithubBoardFilterEvaluator(board, login, today)
            return board.items.filter { item ->
                parsed.conditions.all { evaluator.matches(item, it) }
            }.map { it.itemId }
        }

        describe("GithubBoardFilterParser는") {
            it("큰따옴표 안의 공백과 쉼표를 하나의 값으로 유지한다") {
                GithubBoardFilterParser.tokenize("""status:"In Progress",Done label:bug""") shouldContainExactly
                    listOf("""status:"In Progress",Done""", "label:bug")
                GithubBoardFilterParser.splitValues(""""In Progress",Done""") shouldContainExactly
                    listOf("In Progress", "Done")
            }

            it("해석하지 못한 토큰을 버리지 않고 돌려준다") {
                val parsed = GithubBoardFilterParser.parse(
                    "reason:completed updated:@today type:bug is:open nope:1",
                    board,
                )

                parsed.unsupportedTokens shouldContainExactly
                    listOf("reason:completed", "updated:@today", "type:bug", "nope:1")
                parsed.conditions.map { it.qualifier } shouldContainExactly listOf("is")
            }

            it("field 이름의 대소문자와 공백 대신 쓴 하이픈을 같은 field로 본다") {
                val parsed = GithubBoardFilterParser.parse("story-points:>2 STATUS:todo", board)

                parsed.conditions.map { it.fieldId } shouldContainExactly listOf("F_POINTS", "F_STATUS")
                parsed.unsupportedTokens shouldBe emptyList()
            }

            it("no:는 뒤집은 has:로 해석한다") {
                val condition = GithubBoardFilterParser.parse("no:assignee", board).conditions.single()

                condition.qualifier shouldBe "has"
                condition.negated shouldBe true
            }

            it("숫자 field에 숫자가 아닌 값을 쓰면 해석하지 않는다") {
                GithubBoardFilterParser.parse("story-points:many", board).unsupportedTokens shouldContainExactly
                    listOf("story-points:many")
            }
        }

        describe("GithubBoardFilterEvaluator는") {
            it("담당자의 @me를 요청자 GitHub 사용자 이름으로 바꾼다") {
                matching("assignee:@me") shouldContainExactly listOf("a")
                matching("assignee:@me", login = null) shouldBe emptyList()
            }

            it("같은 qualifier의 쉼표는 OR, 토큰끼리는 AND로 합친다") {
                matching("status:Todo,Done") shouldContainExactly listOf("a", "b")
                matching("status:Todo,Done label:bug") shouldContainExactly listOf("a")
            }

            it("부정과 와일드카드를 지원한다") {
                matching("-status:Done") shouldContainExactly listOf("a", "c", "d")
                matching("label:feature*") shouldContainExactly listOf("b")
            }

            it("is는 상태와 item 종류를 비교한다") {
                matching("is:open") shouldContainExactly listOf("a", "c")
                matching("is:closed") shouldContainExactly listOf("b", "d")
                matching("is:pr is:merged") shouldContainExactly listOf("b")
                matching("is:draft") shouldContainExactly listOf("c")
            }

            it("숫자 비교와 범위를 지원한다") {
                matching("story-points:>=5") shouldContainExactly listOf("c")
                matching("story-points:1..5") shouldContainExactly listOf("a")
            }

            it("날짜는 @today와 상대 날짜를 해석한다") {
                matching("due:@today") shouldContainExactly listOf("a")
                matching("due:>@today+1w") shouldContainExactly listOf("d")
            }

            it("iteration은 제목과 @current, @next를 해석한다") {
                matching("sprint:@current") shouldContainExactly listOf("b")
                matching("sprint:@next") shouldContainExactly listOf("d")
                matching("""sprint:"Sprint 4"""") shouldContainExactly listOf("d")
            }

            it("has와 repo, 일반 텍스트를 지원한다") {
                matching("has:sprint") shouldContainExactly listOf("b", "d")
                matching("-has:label") shouldContainExactly listOf("c", "d")
                matching("repo:my-org/api") shouldContainExactly listOf("d")
                matching("로그인") shouldContainExactly listOf("c")
            }

            it("구조화 파라미터를 뷰 filter와 별도로 적용한다") {
                val evaluator = GithubBoardFilterEvaluator(board, "octocat", today)
                fun query(query: GithubBoardItemQuery) =
                    board.items.filter { evaluator.matches(it, query) }.map { it.itemId }

                query(GithubBoardItemQuery(assignees = listOf("me"))) shouldContainExactly listOf("a")
                query(
                    GithubBoardItemQuery(fields = mapOf("Status" to listOf("Todo", "In Progress"))),
                ) shouldContainExactly
                    listOf("a", "c")
                query(GithubBoardItemQuery(type = "DRAFT_ISSUE")) shouldContainExactly listOf("c")
                query(GithubBoardItemQuery(repo = "api")) shouldContainExactly listOf("d")
                query(GithubBoardItemQuery(q = "오류")) shouldContainExactly listOf("c")
            }
        }
    })
