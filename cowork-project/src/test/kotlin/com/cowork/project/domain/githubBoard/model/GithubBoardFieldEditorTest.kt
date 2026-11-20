package com.cowork.project.domain.githubBoard.model

import com.cowork.project.domain.githubBoard.GithubBoardFixtures
import com.cowork.project.domain.githubBoard.event.GithubProjectField
import com.cowork.project.domain.githubBoard.presentation.data.request.SetGithubBoardFieldReqDto
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException

class GithubBoardFieldEditorTest :
    DescribeSpec({
        val text = GithubProjectField("F_TEXT", "Notes", "TEXT")
        describe("GithubBoardFieldEditor 클래스의 value 메서드는") {
            context("필드 타입에 맞는 값 하나를 지정하면") {
                listOf(
                    Triple(text, SetGithubBoardFieldReqDto(text = ""), mapOf("text" to "")),
                    Triple(GithubBoardFixtures.points, SetGithubBoardFieldReqDto(number = 0.0), mapOf("number" to 0.0)),
                    Triple(
                        GithubBoardFixtures.points,
                        SetGithubBoardFieldReqDto(number = -2.5),
                        mapOf("number" to -2.5),
                    ),
                    Triple(
                        GithubBoardFixtures.due,
                        SetGithubBoardFieldReqDto(date = "2028-02-29"),
                        mapOf("date" to "2028-02-29"),
                    ),
                    Triple(
                        GithubBoardFixtures.status,
                        SetGithubBoardFieldReqDto(singleSelectOptionId = "opt-todo"),
                        mapOf("singleSelectOptionId" to "opt-todo"),
                    ),
                    Triple(
                        GithubBoardFixtures.sprint,
                        SetGithubBoardFieldReqDto(iterationId = "it-1"),
                        mapOf("iterationId" to "it-1"),
                    ),
                ).forEach { (field, request, expected) ->
                    it("${field.dataType}의 $expected 값을 GitHub 입력으로 만든다") {
                        GithubBoardFieldEditor.value(field, request) shouldBe expected
                    }
                }
            }
            context("값이 없거나 타입과 범위가 잘못되면") {
                listOf(
                    "빈 요청" to (text to SetGithubBoardFieldReqDto()),
                    "여러 값" to (text to SetGithubBoardFieldReqDto(text = "x", number = 1.0)),
                    "타입 불일치" to (GithubBoardFixtures.points to SetGithubBoardFieldReqDto(text = "1")),
                    "NaN" to (GithubBoardFixtures.points to SetGithubBoardFieldReqDto(number = Double.NaN)),
                    "무한대" to
                        (GithubBoardFixtures.points to SetGithubBoardFieldReqDto(number = Double.POSITIVE_INFINITY)),
                    "없는 날짜" to (GithubBoardFixtures.due to SetGithubBoardFieldReqDto(date = "2026-02-29")),
                    "시간이 있는 날짜" to (
                        GithubBoardFixtures.due to SetGithubBoardFieldReqDto(
                            date = "2026-11-19T00:00:00Z",
                        )
                        ),
                    "연도 0" to (GithubBoardFixtures.due to SetGithubBoardFieldReqDto(date = "0000-01-01")),
                    "다른 필드의 옵션" to (
                        GithubBoardFixtures.status to SetGithubBoardFieldReqDto(
                            singleSelectOptionId = "other",
                        )
                        ),
                    "없는 반복 주기" to (GithubBoardFixtures.sprint to SetGithubBoardFieldReqDto(iterationId = "other")),
                ).forEach { (name, input) ->
                    it("$name 요청을 거부한다") {
                        shouldThrow<ExpectedException> { GithubBoardFieldEditor.value(input.first, input.second) }
                            .statusCode shouldBe HttpStatus.BAD_REQUEST
                    }
                }
            }
        }

        describe("GithubBoardFieldEditor 클래스의 requireEditable 메서드는") {
            context("기본 속성 또는 존재하지 않는 필드이면") {
                it("기본 속성을 필드 편집으로 우회할 수 없다") {
                    val board = GithubBoardFixtures.board(emptyList()).copy(
                        fields = listOf(GithubProjectField("title", "Title", "TITLE")),
                    )
                    shouldThrow<ExpectedException> { GithubBoardFieldEditor.requireEditable(board, "title") }
                        .statusCode shouldBe HttpStatus.BAD_REQUEST
                }
                it("없는 필드는 찾을 수 없다고 응답한다") {
                    shouldThrow<ExpectedException> {
                        GithubBoardFieldEditor.requireEditable(GithubBoardFixtures.board(emptyList()), "missing")
                    }.statusCode shouldBe HttpStatus.NOT_FOUND
                }
            }
        }
    })
