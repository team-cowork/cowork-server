package com.cowork.channel.domain.meetingNote.service.impl

import com.cowork.channel.domain.meetingNote.entity.MeetingNote
import com.cowork.channel.domain.meetingNote.entity.MeetingNoteTemplate
import com.cowork.channel.domain.meetingNote.entity.SectionType
import com.cowork.channel.domain.meetingNote.entity.TemplateSection
import com.cowork.channel.domain.meetingNote.presentation.data.request.UpdateTemplateSectionRequest
import com.cowork.channel.domain.meetingNote.repository.MeetingNoteRepository
import com.cowork.channel.domain.meetingNote.repository.MeetingNoteTemplateRepository
import com.cowork.channel.domain.meetingNote.repository.TemplateSectionRepository
import com.cowork.channel.domain.meetingNote.service.MeetingNoteAccessGuard
import com.cowork.channel.domain.meetingNote.service.support.MeetingNoteContentSupport
import com.cowork.channel.domain.meetingNote.service.support.MeetingNoteTemplateLookupSupport
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException
import tools.jackson.databind.json.JsonMapper
import java.util.Optional

class UpdateTemplateSectionServiceImplTest {

    private val templateRepository = mockk<MeetingNoteTemplateRepository>(relaxed = true)
    private val sectionRepository = mockk<TemplateSectionRepository>(relaxed = true)
    private val meetingNoteRepository = mockk<MeetingNoteRepository>(relaxed = true)
    private val meetingNoteAccessGuard = mockk<MeetingNoteAccessGuard>(relaxed = true)
    private val lookupSupport = MeetingNoteTemplateLookupSupport(templateRepository, sectionRepository)

    private val service = UpdateTemplateSectionServiceImpl(
        meetingNoteRepository,
        meetingNoteAccessGuard,
        lookupSupport,
        MeetingNoteContentSupport(JsonMapper.builder().build()),
    )

    private fun template(
        id: Long = 10L,
        channelId: Long = 1L,
        name: String = "기본 템플릿",
        isActive: Boolean = false,
        createdBy: Long = 1L,
    ) = MeetingNoteTemplate(id = id, channelId = channelId, name = name, isActive = isActive, createdBy = createdBy)

    private fun section(
        id: Long = 20L,
        templateId: Long = 10L,
        title: String = "회의 제목",
        type: SectionType = SectionType.TEXT,
    ) = TemplateSection(id = id, templateId = templateId, title = title, type = type)

    private fun note(id: Long, content: String) =
        MeetingNote(id = id, channelId = 1L, templateId = 10L, title = "회의록", content = content, createdBy = 7L)

    private fun givenSection(sec: TemplateSection) {
        every { templateRepository.findById(10L) } returns Optional.of(template())
        every { sectionRepository.findById(20L) } returns Optional.of(sec)
    }

    @Test
    fun `updateSection은 전달된 필드만 수정`() {
        val sec = section(type = SectionType.TEXT)
        every { meetingNoteAccessGuard.requireChannelAccessForUpdate(1L, 7L) } returns Unit
        every { templateRepository.findById(10L) } returns Optional.of(template())
        every { sectionRepository.findById(20L) } returns Optional.of(sec)

        val result = service.updateSection(7L, 1L, 10L, 20L, UpdateTemplateSectionRequest(title = "수정된 제목"))
        assertEquals("수정된 제목", result.title)
        assertEquals("TEXT", result.type)
    }

    @Test
    fun `updateSection은 다른 템플릿의 섹션이면 NOT_FOUND`() {
        every { meetingNoteAccessGuard.requireChannelAccessForUpdate(1L, 7L) } returns Unit
        every { templateRepository.findById(10L) } returns Optional.of(template(id = 10L))
        every { sectionRepository.findById(20L) } returns Optional.of(section(templateId = 999L))

        val ex = assertThrows(ExpectedException::class.java) {
            service.updateSection(7L, 1L, 10L, 20L, UpdateTemplateSectionRequest(title = "x"))
        }
        assertEquals(HttpStatus.NOT_FOUND, ex.statusCode)
    }

    @Test
    fun `updateSection은 제목을 바꾸면 회의록 content의 키를 순서대로 옮긴다`() {
        givenSection(section(title = "안건"))
        val target = note(1L, """{"참석자":"민수","안건":"점심 메뉴","결정":"김밥"}""")
        every { meetingNoteRepository.findAllByTemplateId(10L) } returns listOf(target)

        service.updateSection(7L, 1L, 10L, 20L, UpdateTemplateSectionRequest(title = "논의 주제"))

        assertEquals("""{"참석자":"민수","논의 주제":"점심 메뉴","결정":"김밥"}""", target.content)
    }

    @Test
    fun `updateSection은 새 제목 키에 남아 있던 삭제된 섹션 내용을 덮어쓴다`() {
        givenSection(section(title = "안건"))
        val target = note(1L, """{"메모":"다음엔 일찍 시작","안건":"점심 메뉴"}""")
        every { meetingNoteRepository.findAllByTemplateId(10L) } returns listOf(target)

        service.updateSection(7L, 1L, 10L, 20L, UpdateTemplateSectionRequest(title = "메모"))

        assertEquals("""{"메모":"점심 메뉴"}""", target.content)
    }

    @Test
    fun `updateSection은 옮길 키가 없거나 JSON 객체가 아닌 회의록은 그대로 둔다`() {
        givenSection(section(title = "안건"))
        val withoutKey = note(1L, """{"결정": "김밥"}""")
        val notObject = note(2L, "그냥 텍스트")
        every { meetingNoteRepository.findAllByTemplateId(10L) } returns listOf(withoutKey, notObject)

        service.updateSection(7L, 1L, 10L, 20L, UpdateTemplateSectionRequest(title = "논의 주제"))

        assertEquals("""{"결정": "김밥"}""", withoutKey.content)
        assertEquals("그냥 텍스트", notObject.content)
    }

    @Test
    fun `updateSection은 제목이 그대로면 회의록을 읽지 않는다`() {
        givenSection(section(title = "안건"))

        service.updateSection(7L, 1L, 10L, 20L, UpdateTemplateSectionRequest(title = "안건", isRequired = true))

        verify(exactly = 0) { meetingNoteRepository.findAllByTemplateId(any()) }
        verify(exactly = 0) { sectionRepository.existsByTemplateIdAndTitleAndIdNot(any(), any(), any()) }
    }

    @Test
    fun `updateSection은 같은 템플릿에 같은 제목의 섹션이 있으면 CONFLICT`() {
        val sec = section(title = "안건")
        givenSection(sec)
        every { sectionRepository.existsByTemplateIdAndTitleAndIdNot(10L, "결정", 20L) } returns true

        val ex = assertThrows(ExpectedException::class.java) {
            service.updateSection(7L, 1L, 10L, 20L, UpdateTemplateSectionRequest(title = "결정"))
        }
        assertEquals(HttpStatus.CONFLICT, ex.statusCode)
        assertEquals("안건", sec.title)
        verify(exactly = 0) { meetingNoteRepository.findAllByTemplateId(any()) }
    }

    @Test
    fun `updateSection은 대소문자만 바꿀 때 자기 자신을 중복으로 보지 않는다`() {
        givenSection(section(title = "memo"))
        // DB collation은 대소문자를 무시하므로 자기 자신까지 포함하면 중복으로 잡힌다.
        every { sectionRepository.existsByTemplateIdAndTitle(10L, "Memo") } returns true
        every { sectionRepository.existsByTemplateIdAndTitleAndIdNot(10L, "Memo", 20L) } returns false
        val target = note(1L, """{"memo":"점심 메뉴"}""")
        every { meetingNoteRepository.findAllByTemplateId(10L) } returns listOf(target)

        val result = service.updateSection(7L, 1L, 10L, 20L, UpdateTemplateSectionRequest(title = "Memo"))

        assertEquals("Memo", result.title)
        assertEquals("""{"Memo":"점심 메뉴"}""", target.content)
    }
}
