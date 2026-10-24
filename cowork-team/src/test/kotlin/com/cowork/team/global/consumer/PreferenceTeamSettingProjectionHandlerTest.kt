package com.cowork.team.global.consumer

import com.cowork.team.domain.team.projection.TeamSettingProjection
import com.cowork.team.domain.team.projection.TeamSettingProjectionRepository
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.time.Instant
import java.util.Optional

class PreferenceTeamSettingProjectionHandlerTest :
    DescribeSpec({
        lateinit var repository: TeamSettingProjectionRepository
        lateinit var handler: PreferenceTeamSettingProjectionHandler

        val version = Instant.parse("2026-10-24T01:02:03.000001Z")

        beforeEach {
            repository = mockk()
            handler = PreferenceTeamSettingProjectionHandler(repository)
            every { repository.save(any()) } answers { firstArg() }
        }

        describe("PreferenceTeamSettingProjectionHandler 클래스의 handle 메서드는") {
            context("닉네임 형식 필드가 없는 과거 record와 같은 version의 snapshot을 받으면") {
                it("snapshot의 닉네임 형식 안내로 덮어쓴다") {
                    val existing = TeamSettingProjection(teamId = 3L, sourceOccurredAt = version)
                    every { repository.findById(3L) } returns Optional.of(existing)

                    handler.handle(event(version, enforced = true, example = "홍길동 / 프론트엔드"))

                    existing.nicknameFormatEnforced shouldBe true
                    existing.nicknameFormatExample shouldBe "홍길동 / 프론트엔드"
                }
            }

            context("저장된 version보다 오래된 record를 받으면") {
                it("projection을 바꾸지 않는다") {
                    val existing = TeamSettingProjection(
                        teamId = 3L,
                        nicknameFormatEnforced = true,
                        nicknameFormatExample = "2학년 홍길동",
                        sourceOccurredAt = version,
                    )
                    every { repository.findById(3L) } returns Optional.of(existing)

                    handler.handle(event(version.minusSeconds(1), enforced = false, example = null))

                    existing.nicknameFormatEnforced shouldBe true
                    verify(exactly = 0) { repository.save(any()) }
                }
            }

            context("예시 없이 형식 안내만 켜진 record를 받으면") {
                it("안내를 끈 상태로 저장한다") {
                    val saved = slot<TeamSettingProjection>()
                    every { repository.findById(3L) } returns Optional.empty()
                    every { repository.save(capture(saved)) } answers { firstArg() }

                    handler.handle(event(version, enforced = true, example = " "))

                    saved.captured.nicknameFormatEnforced shouldBe false
                    saved.captured.nicknameFormatExample shouldBe null
                }
            }
        }
    }) {
    companion object {
        private fun event(occurredAt: Instant, enforced: Boolean?, example: String?) = PreferenceTeamSettingEvent(
            eventType = "UPSERT",
            schemaVersion = 1,
            teamId = 3L,
            nicknameFormatEnforced = enforced,
            nicknameFormatExample = example,
            occurredAt = occurredAt,
        )
    }
}
