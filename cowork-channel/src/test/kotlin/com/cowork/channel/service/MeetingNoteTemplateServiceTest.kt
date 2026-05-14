package com.cowork.channel.service

import com.cowork.channel.domain.*
import com.cowork.channel.dto.*
import com.cowork.channel.repository.ChannelMemberRepository
import com.cowork.channel.repository.ChannelRepository
import com.cowork.channel.repository.MeetingNoteTemplateRepository
import com.cowork.channel.repository.TemplateSectionRepository
import io.mockk.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException
import java.util.Optional

class MeetingNoteTemplateServiceTest {

    private val templateRepository = mockk<MeetingNoteTemplateRepository>(relaxed = true)
    private val sectionRepository = mockk<TemplateSectionRepository>(relaxed = true)
    private val channelRepository = mockk<ChannelRepository>()
    private val channelMemberRepository = mockk<ChannelMemberRepository>()

    private val service = MeetingNoteTemplateService(
        templateRepository, sectionRepository, channelRepository, channelMemberRepository
    )

    private fun channel(id: Long = 1L, name: String = "ch", createdBy: Long = 1L) = Channel(
        id = id, teamId = 100L, name = name, type = ChannelType.TEXT,
        viewType = ChannelViewType.MEETING_NOTE, description = null,
        isPrivate = false, createdBy = createdBy,
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

    // ──────────────────────────── listTemplates ────────────────────────────

    @Test
    fun `listTemplates는 채널 멤버이면 목록 반환`() {
        every { channelRepository.existsById(1L) } returns true
        every { channelMemberRepository.existsByChannelIdAndUserId(1L, 7L) } returns true
        every { templateRepository.findAllByChannelIdOrderByIdAsc(1L) } returns listOf(template())
        every { sectionRepository.findAllByTemplateIdOrderByIdAsc(10L) } returns emptyList()

        val result = service.listTemplates(7L, 1L)
        assertEquals(1, result.size)
        assertEquals(10L, result[0].id)
    }

    @Test
    fun `listTemplates는 채널 비멤버이면 FORBIDDEN`() {
        every { channelRepository.existsById(1L) } returns true
        every { channelMemberRepository.existsByChannelIdAndUserId(1L, 7L) } returns false

        val ex = assertThrows(ExpectedException::class.java) {
            service.listTemplates(7L, 1L)
        }
        assertEquals(HttpStatus.FORBIDDEN, ex.statusCode)
    }

    // ──────────────────────────── getTemplate ────────────────────────────

    @Test
    fun `getTemplate은 섹션을 포함해서 반환`() {
        every { channelRepository.existsById(1L) } returns true
        every { channelMemberRepository.existsByChannelIdAndUserId(1L, 7L) } returns true
        every { templateRepository.findById(10L) } returns Optional.of(template())
        every { sectionRepository.findAllByTemplateIdOrderByIdAsc(10L) } returns listOf(section())

        val result = service.getTemplate(7L, 1L, 10L)
        assertEquals(1, result.sections.size)
        assertEquals("회의 제목", result.sections[0].title)
    }

    @Test
    fun `getTemplate은 다른 채널 템플릿이면 NOT_FOUND`() {
        every { channelRepository.existsById(1L) } returns true
        every { channelMemberRepository.existsByChannelIdAndUserId(1L, 7L) } returns true
        every { templateRepository.findById(10L) } returns Optional.of(template(channelId = 999L))

        val ex = assertThrows(ExpectedException::class.java) {
            service.getTemplate(7L, 1L, 10L)
        }
        assertEquals(HttpStatus.NOT_FOUND, ex.statusCode)
    }

    @Test
    fun `getTemplate은 존재하지 않는 id이면 NOT_FOUND`() {
        every { channelRepository.existsById(1L) } returns true
        every { channelMemberRepository.existsByChannelIdAndUserId(1L, 7L) } returns true
        every { templateRepository.findById(999L) } returns Optional.empty()

        val ex = assertThrows(ExpectedException::class.java) {
            service.getTemplate(7L, 1L, 999L)
        }
        assertEquals(HttpStatus.NOT_FOUND, ex.statusCode)
    }

    // ──────────────────────────── createTemplate ────────────────────────────

    @Test
    fun `createTemplate은 isActive=false로 저장되고 sections는 빈 리스트`() {
        every { channelRepository.existsById(1L) } returns true
        every { channelMemberRepository.existsByChannelIdAndUserId(1L, 7L) } returns true
        val saved = slot<MeetingNoteTemplate>()
        every { templateRepository.save(capture(saved)) } answers { saved.captured }

        val result = service.createTemplate(7L, 1L, CreateMeetingNoteTemplateRequest("스프린트 회의"))
        assertEquals("스프린트 회의", result.name)
        assertFalse(result.isActive)
        assertTrue(result.sections.isEmpty())
        assertFalse(saved.captured.isActive)
    }

    // ──────────────────────────── updateTemplate ────────────────────────────

    @Test
    fun `updateTemplate은 이름을 수정함`() {
        val tmpl = template(name = "old")
        every { channelRepository.existsById(1L) } returns true
        every { channelMemberRepository.existsByChannelIdAndUserId(1L, 7L) } returns true
        every { templateRepository.findById(10L) } returns Optional.of(tmpl)
        every { sectionRepository.findAllByTemplateIdOrderByIdAsc(10L) } returns emptyList()

        val result = service.updateTemplate(7L, 1L, 10L, UpdateMeetingNoteTemplateRequest("new"))
        assertEquals("new", result.name)
        assertEquals("new", tmpl.name)
    }

    // ──────────────────────────── deleteTemplate ────────────────────────────

    @Test
    fun `deleteTemplate은 활성 템플릿이면 BAD_REQUEST`() {
        every { channelRepository.existsById(1L) } returns true
        every { channelMemberRepository.existsByChannelIdAndUserId(1L, 7L) } returns true
        every { templateRepository.findById(10L) } returns Optional.of(template(isActive = true))

        val ex = assertThrows(ExpectedException::class.java) {
            service.deleteTemplate(7L, 1L, 10L)
        }
        assertEquals(HttpStatus.BAD_REQUEST, ex.statusCode)
        verify(exactly = 0) { templateRepository.delete(any()) }
    }

    @Test
    fun `deleteTemplate은 비활성 템플릿은 섹션과 함께 삭제`() {
        val tmpl = template(isActive = false)
        val sec = section()
        every { channelRepository.existsById(1L) } returns true
        every { channelMemberRepository.existsByChannelIdAndUserId(1L, 7L) } returns true
        every { templateRepository.findById(10L) } returns Optional.of(tmpl)
        every { sectionRepository.findAllByTemplateIdOrderByIdAsc(10L) } returns listOf(sec)

        service.deleteTemplate(7L, 1L, 10L)

        verify { sectionRepository.deleteAll(listOf(sec)) }
        verify { templateRepository.delete(tmpl) }
    }

    // ──────────────────────────── activateTemplate ────────────────────────────

    @Test
    fun `activateTemplate은 기존 활성 템플릿을 비활성화하고 대상을 활성화`() {
        val current = template(id = 5L, isActive = true)
        val next = template(id = 10L, isActive = false)
        every { channelRepository.existsById(1L) } returns true
        every { channelMemberRepository.existsByChannelIdAndUserId(1L, 7L) } returns true
        every { templateRepository.findById(10L) } returns Optional.of(next)
        every { templateRepository.findByChannelIdAndIsActiveTrue(1L) } returns current
        every { sectionRepository.findAllByTemplateIdOrderByIdAsc(10L) } returns emptyList()

        service.activateTemplate(7L, 1L, 10L)

        assertFalse(current.isActive)
        assertTrue(next.isActive)
    }

    @Test
    fun `activateTemplate은 이미 활성 상태인 템플릿을 다시 활성화해도 오류 없음`() {
        val tmpl = template(id = 10L, isActive = true)
        every { channelRepository.existsById(1L) } returns true
        every { channelMemberRepository.existsByChannelIdAndUserId(1L, 7L) } returns true
        every { templateRepository.findById(10L) } returns Optional.of(tmpl)
        every { templateRepository.findByChannelIdAndIsActiveTrue(1L) } returns tmpl
        every { sectionRepository.findAllByTemplateIdOrderByIdAsc(10L) } returns emptyList()

        service.activateTemplate(7L, 1L, 10L)
        assertTrue(tmpl.isActive)
    }

    @Test
    fun `activateTemplate은 기존 활성 템플릿이 없어도 정상 동작`() {
        val next = template(id = 10L, isActive = false)
        every { channelRepository.existsById(1L) } returns true
        every { channelMemberRepository.existsByChannelIdAndUserId(1L, 7L) } returns true
        every { templateRepository.findById(10L) } returns Optional.of(next)
        every { templateRepository.findByChannelIdAndIsActiveTrue(1L) } returns null
        every { sectionRepository.findAllByTemplateIdOrderByIdAsc(10L) } returns emptyList()

        service.activateTemplate(7L, 1L, 10L)
        assertTrue(next.isActive)
    }

    // ──────────────────────────── addSection ────────────────────────────

    @Test
    fun `addSection은 섹션 타입을 파싱해서 저장`() {
        every { channelRepository.existsById(1L) } returns true
        every { channelMemberRepository.existsByChannelIdAndUserId(1L, 7L) } returns true
        every { templateRepository.findById(10L) } returns Optional.of(template())
        val saved = slot<TemplateSection>()
        every { sectionRepository.save(capture(saved)) } answers { saved.captured }

        val result = service.addSection(7L, 1L, 10L, CreateTemplateSectionRequest("비고", "MARKDOWN", isRequired = false))
        assertEquals("비고", result.title)
        assertEquals("MARKDOWN", result.type)
        assertEquals(SectionType.MARKDOWN, saved.captured.type)
    }

    @Test
    fun `addSection은 유효하지 않은 타입이면 BAD_REQUEST`() {
        every { channelRepository.existsById(1L) } returns true
        every { channelMemberRepository.existsByChannelIdAndUserId(1L, 7L) } returns true
        every { templateRepository.findById(10L) } returns Optional.of(template())

        val ex = assertThrows(ExpectedException::class.java) {
            service.addSection(7L, 1L, 10L, CreateTemplateSectionRequest("x", "INVALID"))
        }
        assertEquals(HttpStatus.BAD_REQUEST, ex.statusCode)
    }

    // ──────────────────────────── updateSection ────────────────────────────

    @Test
    fun `updateSection은 전달된 필드만 수정`() {
        val sec = section(type = SectionType.TEXT)
        every { channelRepository.existsById(1L) } returns true
        every { channelMemberRepository.existsByChannelIdAndUserId(1L, 7L) } returns true
        every { templateRepository.findById(10L) } returns Optional.of(template())
        every { sectionRepository.findById(20L) } returns Optional.of(sec)

        val result = service.updateSection(7L, 1L, 10L, 20L, UpdateTemplateSectionRequest(title = "수정된 제목"))
        assertEquals("수정된 제목", result.title)
        assertEquals("TEXT", result.type)
    }

    @Test
    fun `updateSection은 다른 템플릿의 섹션이면 NOT_FOUND`() {
        every { channelRepository.existsById(1L) } returns true
        every { channelMemberRepository.existsByChannelIdAndUserId(1L, 7L) } returns true
        every { templateRepository.findById(10L) } returns Optional.of(template(id = 10L))
        every { sectionRepository.findById(20L) } returns Optional.of(section(templateId = 999L))

        val ex = assertThrows(ExpectedException::class.java) {
            service.updateSection(7L, 1L, 10L, 20L, UpdateTemplateSectionRequest(title = "x"))
        }
        assertEquals(HttpStatus.NOT_FOUND, ex.statusCode)
    }

    // ──────────────────────────── deleteSection ────────────────────────────

    @Test
    fun `deleteSection은 정상적으로 섹션을 삭제`() {
        val sec = section()
        every { channelRepository.existsById(1L) } returns true
        every { channelMemberRepository.existsByChannelIdAndUserId(1L, 7L) } returns true
        every { templateRepository.findById(10L) } returns Optional.of(template())
        every { sectionRepository.findById(20L) } returns Optional.of(sec)

        service.deleteSection(7L, 1L, 10L, 20L)
        verify { sectionRepository.delete(sec) }
    }

    // ──────────────────────────── createDefaultTemplate ────────────────────────────

    @Test
    fun `createDefaultTemplate은 채널명으로 이름 설정 후 isActive=true로 저장`() {
        val ch = channel(id = 1L, name = "기획 회의", createdBy = 5L)
        val savedTemplate = slot<MeetingNoteTemplate>()
        every { templateRepository.save(capture(savedTemplate)) } answers { savedTemplate.captured.also { it.javaClass.getDeclaredField("id").also { f -> f.isAccessible = true }.set(it, 10L) } }
        every { sectionRepository.save(any<TemplateSection>()) } answers { firstArg() }

        service.createDefaultTemplate(ch)

        assertEquals("기획 회의 - 회의록 템플릿", savedTemplate.captured.name)
        assertTrue(savedTemplate.captured.isActive)
        assertEquals(5L, savedTemplate.captured.createdBy)
        verify(exactly = 6) { sectionRepository.save(any<TemplateSection>()) }
    }

    @Test
    fun `createDefaultTemplate은 기본 섹션 6개를 올바른 타입으로 생성`() {
        val ch = channel()
        val savedTemplate = slot<MeetingNoteTemplate>()
        every { templateRepository.save(capture(savedTemplate)) } answers { savedTemplate.captured.also { it.javaClass.getDeclaredField("id").also { f -> f.isAccessible = true }.set(it, 10L) } }
        val savedSections = mutableListOf<TemplateSection>()
        every { sectionRepository.save(any<TemplateSection>()) } answers {
            (firstArg<TemplateSection>()).also { savedSections.add(it) }
        }

        service.createDefaultTemplate(ch)

        val types = savedSections.map { it.type }
        assertEquals(SectionType.TEXT, types[0])
        assertEquals(SectionType.DATETIME, types[1])
        assertEquals(SectionType.USER_LIST, types[2])
        assertEquals(SectionType.MARKDOWN, types[3])
        assertEquals(SectionType.MARKDOWN, types[4])
        assertEquals(SectionType.DATETIME, types[5])
    }

    @Test
    fun `createDefaultTemplate은 필수 섹션 4개, 선택 섹션 2개를 생성`() {
        val ch = channel()
        val savedTemplate = slot<MeetingNoteTemplate>()
        every { templateRepository.save(capture(savedTemplate)) } answers { savedTemplate.captured.also { it.javaClass.getDeclaredField("id").also { f -> f.isAccessible = true }.set(it, 10L) } }
        val savedSections = mutableListOf<TemplateSection>()
        every { sectionRepository.save(any<TemplateSection>()) } answers {
            (firstArg<TemplateSection>()).also { savedSections.add(it) }
        }

        service.createDefaultTemplate(ch)

        assertEquals(4, savedSections.count { it.isRequired })
        assertEquals(2, savedSections.count { !it.isRequired })
    }
}
