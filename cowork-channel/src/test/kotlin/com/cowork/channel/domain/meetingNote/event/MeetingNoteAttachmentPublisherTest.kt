package com.cowork.channel.domain.meetingNote.event

import com.cowork.channel.domain.meetingNote.entity.MeetingNote
import com.cowork.channel.domain.meetingNote.entity.MeetingNoteAttachment
import com.cowork.channel.domain.meetingNote.entity.MeetingNoteAttachmentState
import com.cowork.channel.domain.meetingNote.repository.MeetingNoteAttachmentStateRepository
import com.cowork.channel.global.outbox.OutboxWriter
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import tools.jackson.databind.json.JsonMapper
import java.time.Instant

class MeetingNoteAttachmentPublisherTest :
    DescribeSpec({
        val states = mockk<MeetingNoteAttachmentStateRepository>()
        val outbox = mockk<OutboxWriter>(relaxed = true)
        val mapper = JsonMapper.builder().build()
        val publisher = MeetingNoteAttachmentPublisher(states, outbox, mapper)
        val note = MeetingNote(id = 3, channelId = 1, templateId = 1, title = "회의", content = "{}", createdBy = 42)
        val saved = slot<MeetingNoteAttachmentState>()

        beforeEach {
            every { states.findForUpdate(3) } returns null
            every { states.save(capture(saved)) } answers { saved.captured }
        }

        describe("첨부파일 상태 발행") {
            it("저장된 첨부 목록과 소유자를 outbox에 기록한다") {
                note.attachments.add(MeetingNoteAttachment("chat-files/1/42/file.pdf", "자료.pdf"))
                publisher.publish(note)
                saved.captured.authorId shouldBe 42
                mapper.readTree(saved.captured.attachmentsJson).size() shouldBe 1
                verify { outbox.enqueue(MeetingNoteAttachmentPublisher.TOPIC, "3", any(), any(), any()) }
            }
            it("삭제 시 이전 버전보다 최신인 빈 목록 tombstone을 발행한다") {
                val previous =
                    MeetingNoteAttachmentState(3, 1, 42, "[]", stateOccurredAt = Instant.parse("2099-01-01T00:00:00Z"))
                val previousTime = previous.stateOccurredAt
                every { states.findForUpdate(3) } returns previous
                publisher.publish(note, deleted = true)
                saved.captured.deleted shouldBe true
                saved.captured.attachmentsJson shouldBe "[]"
                saved.captured.stateOccurredAt shouldBeGreaterThan previousTime
            }
        }
    })
