package com.cowork.team.domain.team.projection

import com.cowork.team.global.projection.ProjectionReadinessState
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Instant
import java.util.Optional

class TeamSettingProjectionReaderTest :
    DescribeSpec({
        lateinit var repository: TeamSettingProjectionRepository
        lateinit var readinessState: ProjectionReadinessState
        lateinit var reader: TeamSettingProjectionReader

        beforeEach {
            repository = mockk()
            readinessState = mockk()
            reader = TeamSettingProjectionReader(repository, readinessState)
        }

        describe("TeamSettingProjectionReader 클래스의 getNicknameFormat 메서드는") {
            context("projection 동기화가 끝나지 않았으면") {
                it("예외 없이 안내를 끈 값을 반환한다") {
                    every { readinessState.isReady() } returns false

                    reader.getNicknameFormat(1L) shouldBe NicknameFormatGuide.DISABLED
                    verify(exactly = 0) { repository.findById(any()) }
                }
            }

            context("팀 설정 projection이 없으면") {
                it("안내를 끈 값을 반환한다") {
                    every { readinessState.isReady() } returns true
                    every { repository.findById(1L) } returns Optional.empty()

                    reader.getNicknameFormat(1L) shouldBe NicknameFormatGuide.DISABLED
                }
            }

            context("팀 설정 projection이 있으면") {
                it("저장된 닉네임 형식 안내를 반환한다") {
                    every { readinessState.isReady() } returns true
                    every { repository.findById(1L) } returns Optional.of(
                        TeamSettingProjection(
                            teamId = 1L,
                            nicknameFormatEnforced = true,
                            nicknameFormatExample = "홍길동 / 프론트엔드",
                            sourceOccurredAt = Instant.now(),
                        ),
                    )

                    reader.getNicknameFormat(1L) shouldBe
                        NicknameFormatGuide(enforced = true, example = "홍길동 / 프론트엔드")
                }
            }
        }
    })
