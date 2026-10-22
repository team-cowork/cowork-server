package com.cowork.project.domain.github.entity

import com.cowork.project.domain.github.event.GithubPullRequestFileState
import com.cowork.project.domain.github.event.GithubPullRequestStateEvent
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import java.time.Instant

class GithubPullRequestProjectionTest :
    DescribeSpec({

        val t1 = Instant.parse("2026-01-01T00:00:00Z")
        val t2 = Instant.parse("2026-01-02T00:00:00Z")

        fun projection() = GithubPullRequestProjection(
            id = "my-org/my-repo#7",
            owner = "my-org",
            repo = "my-repo",
            number = 7,
            title = "old",
            author = "octocat",
            state = "open",
            draft = false,
            merged = false,
            htmlUrl = "",
            headRef = "feature",
            baseRef = "main",
            githubCreatedAt = "",
            githubUpdatedAt = "",
            sourceOccurredAt = Instant.EPOCH,
        )

        fun event(title: String, at: Instant, files: List<GithubPullRequestFileState>? = null) =
            GithubPullRequestStateEvent(owner = "my-org", repo = "my-repo", number = 7, title = title, sourceOccurredAt = at, files = files)

        describe("GithubPullRequestProjection 클래스의 apply 메서드는") {
            context("labeled(T2) 뒤에 파일이 실린 synchronize(T1)가 늦게 도착하면") {
                it("다른 필드는 T2를 유지하고 파일만 반영한다") {
                    val projection = projection()
                    projection.apply(event("labeled", t2))

                    val applied = projection.apply(event("synchronize", t1, listOf(GithubPullRequestFileState(filename = "a.kt"))))

                    applied shouldBe true
                    projection.title shouldBe "labeled"
                    projection.files.map { it.filename } shouldBe listOf("a.kt")
                }
            }

            context("파일이 실린 이벤트가 이미 반영된 파일보다 오래된 경우") {
                it("파일을 덮어쓰지 않는다") {
                    val projection = projection()
                    projection.apply(event("new", t2, listOf(GithubPullRequestFileState(filename = "new.kt"))))

                    val applied = projection.apply(event("old", t1, listOf(GithubPullRequestFileState(filename = "old.kt"))))

                    applied shouldBe false
                    projection.files.map { it.filename } shouldBe listOf("new.kt")
                }
            }
        }
    })
