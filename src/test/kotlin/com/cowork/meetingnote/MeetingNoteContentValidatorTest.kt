package com.cowork.meetingnote

import com.cowork.channel.meetingnote.domain.SectionType
import com.cowork.channel.meetingnote.domain.TemplateSection
import com.cowork.channel.meetingnote.service.MeetingNoteContentValidator
import com.cowork.global.error.BusinessException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class MeetingNoteContentValidatorTest {

    private val sections = listOf(
        TemplateSection(templateId = 1, title = "안건", type = SectionType.MARKDOWN, placeholder = null, required = true, position = 0, id = 1),
        TemplateSection(templateId = 1, title = "회의 날짜", type = SectionType.DATE, placeholder = null, required = false, position = 1, id = 2),
        TemplateSection(templateId = 1, title = "참석자", type = SectionType.USER_LIST, placeholder = null, required = false, position = 2, id = 3),
    )

    @Test
    fun `섹션 정의에 맞는 값을 스냅샷 형태로 변환한다`() {
        val result = MeetingNoteContentValidator.validate(
            sections,
            mapOf(1L to "# 안건", 2L to "2025-10-01", 3L to listOf(1, 2, 2)),
            teamMemberIds = setOf(1L, 2L),
        )

        assertEquals(listOf("안건", "회의 날짜", "참석자"), result.map { it.title })
        assertEquals("2025-10-01", result[1].value)
        assertEquals(listOf(1L, 2L), result[2].value)
    }

    @Test
    fun `필수 섹션이 비어 있으면 거부한다`() {
        assertThrows<BusinessException> {
            MeetingNoteContentValidator.validate(sections, mapOf(1L to "  "), teamMemberIds = emptySet())
        }
    }

    @Test
    fun `날짜 형식이 잘못되면 거부한다`() {
        assertThrows<BusinessException> {
            MeetingNoteContentValidator.validate(sections, mapOf(1L to "안건", 2L to "10/01/2025"), teamMemberIds = emptySet())
        }
    }

    @Test
    fun `팀 멤버가 아닌 사용자는 참석자로 지정할 수 없다`() {
        assertThrows<BusinessException> {
            MeetingNoteContentValidator.validate(sections, mapOf(1L to "안건", 3L to listOf(9)), teamMemberIds = setOf(1L))
        }
    }

    @Test
    fun `템플릿에 없는 섹션 값은 거부한다`() {
        assertThrows<BusinessException> {
            MeetingNoteContentValidator.validate(sections, mapOf(1L to "안건", 99L to "?"), teamMemberIds = emptySet())
        }
    }
}
