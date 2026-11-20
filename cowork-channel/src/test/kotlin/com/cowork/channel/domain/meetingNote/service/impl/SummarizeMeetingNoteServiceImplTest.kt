package com.cowork.channel.domain.meetingNote.service.impl

import com.cowork.channel.domain.meetingNote.client.MeetingNoteSummaryClient
import com.cowork.channel.domain.meetingNote.presentation.data.request.SummarizeMeetingNoteRequest
import com.cowork.channel.domain.meetingNote.service.MeetingNoteAccessGuard
import com.cowork.channel.domain.meetingNote.service.SummarizeMeetingNoteService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException

class SummarizeMeetingNoteServiceImplTest :
    DescribeSpec({
        lateinit var access: MeetingNoteAccessGuard
        lateinit var client: MeetingNoteSummaryClient
        lateinit var service: SummarizeMeetingNoteService
        val summary = MeetingNoteSummaryClient.Summary("요약", emptyList(), emptyList())

        beforeEach {
            access = mockk(relaxed = true)
            client = mockk()
            service = SummarizeMeetingNoteServiceImpl(access, client)
        }

        describe("SummarizeMeetingNoteServiceImpl 클래스의 execute 메서드는") {
            context("채널에 접근할 수 없으면") {
                it("외부 호출 전에 접근을 거부한다") {
                    every { access.requireChannelAccess(1, 2) } throws
                        ExpectedException("권한이 없습니다.", HttpStatus.FORBIDDEN)
                    shouldThrow<ExpectedException> { service.execute(2, 1, SummarizeMeetingNoteRequest("", "비공개")) }
                        .statusCode shouldBe HttpStatus.FORBIDDEN
                    verify(exactly = 0) { client.summarize(any(), any()) }
                }
            }

            context("이모지가 12,000자 경계에 걸치면") {
                it("이모지 앞까지 요약하고 잘림을 표시한다") {
                    val prefix = "가".repeat(11_999)
                    every { client.summarize("회의", prefix) } returns summary
                    val result = service.execute(3, 1, SummarizeMeetingNoteRequest("회의", prefix + "😀"))
                    result.truncated shouldBe true
                    result.summary shouldBe "요약"
                }
            }

            context("전체 본문이 입력 한도 안에 들면") {
                it("잘림 표시 없이 요약한다") {
                    every { client.summarize("", "회의") } returns summary
                    service.execute(3, 1, SummarizeMeetingNoteRequest("", " 회의 ")).truncated shouldBe false
                }
            }
        }
    })
