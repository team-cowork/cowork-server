package com.cowork.project.github

import com.cowork.project.domain.github.service.GithubRepoRef
import com.cowork.project.domain.github.service.GithubRepoUrlParser

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class GithubRepoUrlParserTest : DescribeSpec({

    describe("GithubRepoUrlParser 클래스의") {
        describe("parse 메서드는") {
            context("github.com URL이 주어지면") {
                it("owner와 repo로 파싱한다") {
                    val result = GithubRepoUrlParser.parse("https://github.com/my-org/my-repo")

                    result shouldBe GithubRepoRef(owner = "my-org", repo = "my-repo")
                }
            }

            context(".git 접미사가 붙은 URL이 주어지면") {
                it("접미사를 제거하고 파싱한다") {
                    val result = GithubRepoUrlParser.parse("https://github.com/my-org/my-repo.git")

                    result shouldBe GithubRepoRef(owner = "my-org", repo = "my-repo")
                }
            }

            context("github.com이 아닌 호스트가 주어지면") {
                it("null을 반환한다") {
                    val result = GithubRepoUrlParser.parse("https://gitlab.com/my-org/my-repo")

                    result.shouldBeNull()
                }
            }

            context("owner/repo 경로가 부족하면") {
                it("null을 반환한다") {
                    val result = GithubRepoUrlParser.parse("https://github.com/my-org")

                    result.shouldBeNull()
                }
            }

            context("유효하지 않은 URL 형식이 주어지면") {
                it("null을 반환한다") {
                    val result = GithubRepoUrlParser.parse("not a url")

                    result.shouldBeNull()
                }
            }
        }
    }
})
