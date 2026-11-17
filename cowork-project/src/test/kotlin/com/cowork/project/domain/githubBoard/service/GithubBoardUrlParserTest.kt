package com.cowork.project.domain.githubBoard.service

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import team.themoment.sdk.exception.ExpectedException

class GithubBoardUrlParserTest :
    DescribeSpec({
        describe("GithubBoardUrlParser는") {
            it("조직 Project URL에서 소문자 조직과 번호를 읽고 뷰 경로는 무시한다") {
                GithubBoardUrlParser.parse("https://github.com/orgs/My-Org/projects/3/views/2?filterQuery=") shouldBe
                    GithubBoardRef("my-org", 3)
            }

            it("개인 계정 Project는 거부한다") {
                shouldThrow<ExpectedException> {
                    GithubBoardUrlParser.parse("https://github.com/users/octocat/projects/1")
                }
                    .message shouldContain "개인 계정"
            }

            it("GitHub Project URL이 아니면 거부한다") {
                listOf("https://gitlab.com/orgs/a/projects/1", "https://github.com/my-org/app", "not a url").forEach {
                    shouldThrow<ExpectedException> { GithubBoardUrlParser.parse(it) }
                }
            }
        }
    })
