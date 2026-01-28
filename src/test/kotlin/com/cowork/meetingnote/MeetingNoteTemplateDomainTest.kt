package com.cowork.meetingnote

import com.cowork.channel.meetingnote.domain.MeetingNoteTemplate
import com.cowork.channel.meetingnote.domain.SectionType
import com.cowork.channel.meetingnote.domain.TemplateSection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MeetingNoteTemplateDomainTest {

    @Test
    fun `템플릿을 켜고 끌 수 있다`() {
        val template = MeetingNoteTemplate(channelId = 30, name = "주간 회의", createdBy = 1)

        template.activate()
        assertTrue(template.isActive)

        template.deactivate()
        assertFalse(template.isActive)
    }

    @Test
    fun `섹션 안내 문구를 빈 값으로 바꾸면 문구를 지운다`() {
        val section = TemplateSection(
            templateId = 1, title = "내용", type = SectionType.TEXT, placeholder = "내용을 적어 주세요",
            required = false, position = 0,
        )

        section.update(null, SectionType.MARKDOWN, "", true, null)

        assertNull(section.placeholder)
        assertEquals(SectionType.MARKDOWN, section.type)
        assertTrue(section.required)
        assertEquals(0, section.position)
    }
}
