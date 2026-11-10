package com.cowork.channel.domain.channel.entity

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.time.LocalDateTime

class ChannelArchiveTest :
    DescribeSpec({

        fun channel() = Channel(
            id = 1L,
            teamId = 10L,
            name = "general",
            type = ChannelType.TEXT,
            viewType = ChannelViewType.TEXT,
            description = null,
            createdBy = 1L,
        )
        val archivedAt = LocalDateTime.of(2026, 11, 10, 9, 0)

        describe("Channel 클래스의 archive 메서드는") {
            context("활성 채널이면") {
                it("보관 시각과 보관한 사용자를 기록한다") {
                    val channel = channel()

                    channel.archive(7L, archivedAt)

                    channel.isArchived shouldBe true
                    channel.archivedAt shouldBe archivedAt
                    channel.archivedBy shouldBe 7L
                }
            }

            context("이미 보관된 채널이면") {
                it("상태 오류를 던진다") {
                    val channel = channel().apply { archive(7L, archivedAt) }

                    shouldThrow<IllegalStateException> { channel.archive(7L, archivedAt.plusMinutes(1)) }
                }
            }
        }

        describe("Channel 클래스의 unarchive 메서드는") {
            context("보관된 채널이면") {
                it("보관 기록을 지운다") {
                    val channel = channel().apply { archive(7L, archivedAt) }

                    channel.unarchive()

                    channel.isArchived shouldBe false
                    channel.archivedAt.shouldBeNull()
                    channel.archivedBy.shouldBeNull()
                }
            }

            context("활성 채널이면") {
                it("상태 오류를 던진다") {
                    shouldThrow<IllegalStateException> { channel().unarchive() }
                }
            }
        }
    })
