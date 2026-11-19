package com.cowork.channel.domain.meetingNote.service

import com.cowork.channel.domain.meetingNote.client.MeetingNoteSummaryClient
import com.cowork.channel.domain.meetingNote.presentation.data.request.SummarizeMeetingNoteRequest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException

class SummarizeMeetingNoteServiceTest :
    DescribeSpec({
        lateinit var access: MeetingNoteAccessGuard
        lateinit var client: MeetingNoteSummaryClient
        lateinit var service: SummarizeMeetingNoteService
        val summary = MeetingNoteSummaryClient.Summary("요약", emptyList(), emptyList())

        beforeEach {
            access = mockk(relaxed = true)
            client = mockk()
            service = SummarizeMeetingNoteService(access, client)
        }

        describe("회의록 요약") {
            it("채널에 접근할 수 없으면 회의록을 외부로 보내지 않는다") {
                every { access.requireChannelAccess(1, 2) } throws ExpectedException("권한이 없습니다.", HttpStatus.FORBIDDEN)
                shouldThrow<ExpectedException> { service.summarize(2, 1, SummarizeMeetingNoteRequest("", "비공개")) }
                    .statusCode shouldBe HttpStatus.FORBIDDEN
                verify(exactly = 0) { client.summarize(any(), any()) }
            }

            it("12,000자 경계에 걸친 이모지를 자르지 않고 앞부분만 보냈다고 표시한다") {
                val prefix = "가".repeat(11_999)
                every { client.summarize("회의", prefix) } returns summary
                val result = service.summarize(3, 1, SummarizeMeetingNoteRequest("회의", prefix + "😀"))
                result.truncated shouldBe true
                result.summary shouldBe "요약"
            }

            it("전체 본문을 보내면 잘림 표시를 하지 않는다") {
                every { client.summarize("", "회의") } returns summary
                service.summarize(3, 1, SummarizeMeetingNoteRequest("", " 회의 ")).truncated shouldBe false
            }
        }
    })
