package com.cowork.channel.domain.meetingNote.service.impl

import com.cowork.channel.domain.meetingNote.entity.MeetingNote
import com.cowork.channel.domain.meetingNote.entity.MeetingNoteTemplate
import com.cowork.channel.domain.meetingNote.presentation.data.request.CreateMeetingNoteRequest
import com.cowork.channel.domain.meetingNote.repository.MeetingNoteRepository
import com.cowork.channel.domain.meetingNote.repository.MeetingNoteTemplateRepository
import com.cowork.channel.domain.meetingNote.service.MeetingNoteAccessGuard
import com.cowork.channel.domain.meetingNote.service.support.MeetingNoteContentSupport
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException
import tools.jackson.databind.json.JsonMapper
import java.util.Optional

class CreateMeetingNoteServiceImplTest :
    DescribeSpec({

        lateinit var noteRepository: MeetingNoteRepository
        lateinit var templateRepository: MeetingNoteTemplateRepository
        lateinit var accessGuard: MeetingNoteAccessGuard
        lateinit var service: CreateMeetingNoteServiceImpl
        val request = CreateMeetingNoteRequest(7L, "주간 회의", "{\"agenda\":\"API\"}")

        beforeEach {
            noteRepository = mockk(relaxed = true)
            templateRepository = mockk()
            accessGuard = mockk(relaxed = true)
            service = CreateMeetingNoteServiceImpl(
                noteRepository,
                templateRepository,
                accessGuard,
                MeetingNoteContentSupport(JsonMapper.builder().build()),
            )
        }

        describe("CreateMeetingNoteServiceImpl 클래스의 createNote 메서드는") {
            context("content가 JSON 객체가 아니면") {
                it("BAD_REQUEST로 거부한다") {
                    val invalid = CreateMeetingNoteRequest(7L, "주간 회의", "[\"agenda\"]")

                    val error = shouldThrow<ExpectedException> { service.createNote(20L, 1L, invalid) }

                    error.statusCode shouldBe HttpStatus.BAD_REQUEST
                    verify(exactly = 0) { noteRepository.saveAndFlush(any()) }
                }
            }

            context("제목이 200자를 넘으면") {
                it("저장 전에 BAD_REQUEST로 거부한다") {
                    val invalid = CreateMeetingNoteRequest(7L, "a".repeat(201), "{}")

                    val error = shouldThrow<ExpectedException> { service.createNote(20L, 1L, invalid) }

                    error.statusCode shouldBe HttpStatus.BAD_REQUEST
                    verify(exactly = 0) { noteRepository.saveAndFlush(any()) }
                }
            }

            context("템플릿 조회 직후 다른 요청이 템플릿을 삭제해 FK가 저장을 막으면") {
                it("NOT_FOUND로 응답한다") {
                    every { templateRepository.findById(7L) } returns Optional.of(
                        MeetingNoteTemplate(id = 7L, channelId = 1L, name = "기본", createdBy = 20L),
                    )
                    every { noteRepository.saveAndFlush(any()) } throws
                        DataIntegrityViolationException("fk_tb_meeting_notes_template")

                    val error = shouldThrow<ExpectedException> { service.createNote(20L, 1L, request) }

                    error.statusCode shouldBe HttpStatus.NOT_FOUND
                }
            }

            context("요청한 템플릿이 없으면") {
                it("NOT_FOUND로 거부한다") {
                    every { templateRepository.findById(7L) } returns Optional.empty()

                    val error = shouldThrow<ExpectedException> { service.createNote(20L, 1L, request) }

                    error.statusCode shouldBe HttpStatus.NOT_FOUND
                    verify(exactly = 0) { noteRepository.saveAndFlush(any()) }
                }
            }

            context("템플릿이 다른 채널에 속하면") {
                it("해당 템플릿을 노출하지 않고 NOT_FOUND로 응답한다") {
                    every { templateRepository.findById(7L) } returns Optional.of(
                        MeetingNoteTemplate(id = 7L, channelId = 2L, name = "기본", createdBy = 20L),
                    )

                    val error = shouldThrow<ExpectedException> { service.createNote(20L, 1L, request) }

                    error.statusCode shouldBe HttpStatus.NOT_FOUND
                    verify(exactly = 0) { noteRepository.saveAndFlush(any()) }
                }
            }

            context("채널 멤버가 같은 채널 템플릿을 선택하면") {
                it("회의록을 생성한다") {
                    every { templateRepository.findById(7L) } returns Optional.of(
                        MeetingNoteTemplate(id = 7L, channelId = 1L, name = "기본", createdBy = 20L),
                    )
                    val saved = slot<MeetingNote>()
                    every { noteRepository.saveAndFlush(capture(saved)) } answers { saved.captured }

                    val result = service.createNote(20L, 1L, request)

                    result.title shouldBe "주간 회의"
                    saved.captured.channelId shouldBe 1L
                    saved.captured.templateId shouldBe 7L
                    saved.captured.createdBy shouldBe 20L
                }
            }
        }
    })
