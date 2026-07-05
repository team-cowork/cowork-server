package com.cowork.channel.domain.meetingNote.service.impl

import com.cowork.channel.domain.channel.entity.Channel
import com.cowork.channel.domain.channel.entity.ChannelType
import com.cowork.channel.domain.channel.entity.ChannelViewType
import com.cowork.channel.domain.meetingNote.entity.MeetingNoteTemplate
import com.cowork.channel.domain.meetingNote.entity.SectionType
import com.cowork.channel.domain.meetingNote.entity.TemplateSection
import com.cowork.channel.domain.meetingNote.repository.MeetingNoteTemplateRepository
import com.cowork.channel.domain.meetingNote.repository.TemplateSectionRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CreateDefaultMeetingNoteTemplateServiceImplTest {

    private val templateRepository = mockk<MeetingNoteTemplateRepository>(relaxed = true)
    private val sectionRepository = mockk<TemplateSectionRepository>(relaxed = true)

    private val service = CreateDefaultMeetingNoteTemplateServiceImpl(templateRepository, sectionRepository)

    private fun channel(id: Long = 1L, name: String = "ch", createdBy: Long = 1L) = Channel(
        id = id,
        teamId = 100L,
        name = name,
        type = ChannelType.TEXT,
        viewType = ChannelViewType.MEETING_NOTE,
        description = null,
        isPrivate = false,
        createdBy = createdBy,
    )

    @Test
    fun `createDefaultTemplate은 채널명으로 이름 설정 후 isActive=true로 저장`() {
        val ch = channel(id = 1L, name = "기획 회의", createdBy = 5L)
        val savedTemplate = slot<MeetingNoteTemplate>()
        every { templateRepository.save(capture(savedTemplate)) } answers
            {
                savedTemplate.captured.also {
                    it.javaClass.getDeclaredField("id").also { f -> f.isAccessible = true }.set(it, 10L)
                }
            }
        every { sectionRepository.saveAll(any<List<TemplateSection>>()) } answers { firstArg() }

        service.createDefaultTemplate(ch)

        assertEquals("기획 회의 - 회의록 템플릿", savedTemplate.captured.name)
        assertTrue(savedTemplate.captured.isActive)
        assertEquals(5L, savedTemplate.captured.createdBy)
        val savedSectionsSlot = slot<List<TemplateSection>>()
        verify { sectionRepository.saveAll(capture(savedSectionsSlot)) }
        assertEquals(6, savedSectionsSlot.captured.size)
    }

    @Test
    fun `createDefaultTemplate은 기본 섹션 6개를 올바른 타입으로 생성`() {
        val ch = channel()
        val savedTemplate = slot<MeetingNoteTemplate>()
        every { templateRepository.save(capture(savedTemplate)) } answers
            {
                savedTemplate.captured.also {
                    it.javaClass.getDeclaredField("id").also { f -> f.isAccessible = true }.set(it, 10L)
                }
            }
        val savedSectionsSlot = slot<List<TemplateSection>>()
        every { sectionRepository.saveAll(capture(savedSectionsSlot)) } answers { firstArg() }

        service.createDefaultTemplate(ch)

        val types = savedSectionsSlot.captured.map { it.type }
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
        every { templateRepository.save(capture(savedTemplate)) } answers
            {
                savedTemplate.captured.also {
                    it.javaClass.getDeclaredField("id").also { f -> f.isAccessible = true }.set(it, 10L)
                }
            }
        val savedSectionsSlot = slot<List<TemplateSection>>()
        every { sectionRepository.saveAll(capture(savedSectionsSlot)) } answers { firstArg() }

        service.createDefaultTemplate(ch)

        assertEquals(4, savedSectionsSlot.captured.count { it.isRequired })
        assertEquals(2, savedSectionsSlot.captured.count { !it.isRequired })
    }
}
