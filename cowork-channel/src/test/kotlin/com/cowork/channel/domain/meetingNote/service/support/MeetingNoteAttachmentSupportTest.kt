package com.cowork.channel.domain.meetingNote.service.support

import com.cowork.channel.domain.meetingNote.presentation.data.request.MeetingNoteAttachmentRequest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import team.themoment.sdk.exception.ExpectedException

class MeetingNoteAttachmentSupportTest :
    DescribeSpec({
        val support = MeetingNoteAttachmentSupport()
        val key = "chat-files/1/42/123e4567-e89b-12d3-a456-426614174000.pdf"
        val attachment = MeetingNoteAttachmentRequest(key, "회의자료.pdf")

        describe("회의록 첨부파일 검증") {
            it("작성자가 해당 채널에 업로드한 확정 파일을 허용한다") {
                support.validate(listOf(attachment), 1, 42).single().objectKey shouldBe key
            }
            it("다른 사용자와 채널의 파일, 임시 경로, 경로 조작을 거부한다") {
                listOf(
                    key.replace("/42/", "/7/"),
                    key.replace("/1/", "/2/"),
                    key.replace("chat-files", "chat-uploads"),
                    "$key/../secret",
                ).forEach {
                    shouldThrow<ExpectedException> { support.validate(listOf(attachment.copy(objectKey = it)), 1, 42) }
                }
            }
            it("중복 첨부와 개수 초과를 거부한다") {
                shouldThrow<ExpectedException> { support.validate(listOf(attachment, attachment), 1, 42) }
                shouldThrow<ExpectedException> { support.validate(List(21) { attachment }, 1, 42) }
            }
            it("파일명의 공백, 제어 문자와 길이 초과를 거부한다") {
                listOf(" ", "file\r\nname.pdf", "a".repeat(256)).forEach {
                    shouldThrow<ExpectedException> { support.validate(listOf(attachment.copy(name = it)), 1, 42) }
                }
            }
        }
    })
