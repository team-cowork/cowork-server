package com.cowork.project.domain.github.entity

import com.cowork.project.domain.github.event.GithubLabelStateEvent
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import java.time.Instant

class GithubLabelProjectionTest :
    DescribeSpec({

        fun projection() = GithubLabelProjection(
            id = "my-org/my-repo:bug",
            owner = "my-org",
            repo = "my-repo",
            name = "bug",
            color = "d73a4a",
            sourceOccurredAt = Instant.parse("2026-01-01T00:00:00Z"),
        )

        fun event(name: String, at: String) = GithubLabelStateEvent(
            owner = "my-org",
            repo = "my-repo",
            name = name,
            color = "d73a4a",
            sourceOccurredAt = Instant.parse(at),
        )

        describe("GithubLabelProjection 클래스의 apply 메서드는") {
            context("대소문자만 바뀐 더 새로운 이름 변경을 받으면") {
                it("바뀐 이름으로 갱신한다") {
                    val projection = projection()

                    val applied = projection.apply(event("Bug", "2026-01-02T00:00:00Z"))

                    applied shouldBe true
                    projection.name shouldBe "Bug"
                }
            }

            context("더 오래된 이름 변경을 받으면") {
                it("이름을 되돌리지 않는다") {
                    val projection = projection()

                    val applied = projection.apply(event("BUG", "2025-12-31T00:00:00Z"))

                    applied shouldBe false
                    projection.name shouldBe "bug"
                }
            }
        }
    })
