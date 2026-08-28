package com.cowork.project.domain.channel.service

import com.cowork.project.domain.channel.entity.ChannelProjection
import com.cowork.project.domain.channel.repository.ChannelProjectionRepository
import com.cowork.project.global.projection.ProjectionReadinessGate
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException
import java.time.Instant

class ChannelProjectionReaderTest :
    DescribeSpec({
        val repository = mockk<ChannelProjectionRepository>()
        val readinessGate = mockk<ProjectionReadinessGate>()
        val reader = ChannelProjectionReader(repository, readinessGate)

        beforeEach {
            every { readinessGate.requireReady() } just runs
            every { readinessGate.requireCurrent() } just runs
        }

        describe("requireProjectChannel") {
            it("snapshot에 없는 channelId는 존재하지 않는 채널로 판단한다") {
                every { repository.findByIdForUpdate(10L) } returns null andThen null

                shouldThrow<ExpectedException> { reader.requireProjectChannel(10L, 1L) }.statusCode shouldBe
                    HttpStatus.NOT_FOUND
            }

            it("삭제가 확인된 channel은 NOT_FOUND를 반환한다") {
                every { repository.findByIdForUpdate(10L) } returns
                    ChannelProjection(10L, 1L, deleted = true, sourceOccurredAt = Instant.EPOCH)

                shouldThrow<ExpectedException> { reader.requireProjectChannel(10L, 1L) }.statusCode shouldBe
                    HttpStatus.NOT_FOUND
            }

            it("다른 project 소속 channel은 BAD_REQUEST를 반환한다") {
                every { repository.findByIdForUpdate(10L) } returns
                    ChannelProjection(10L, 2L, deleted = false, sourceOccurredAt = Instant.EPOCH)

                shouldThrow<ExpectedException> { reader.requireProjectChannel(10L, 1L) }.statusCode shouldBe
                    HttpStatus.BAD_REQUEST
            }
        }
    })
