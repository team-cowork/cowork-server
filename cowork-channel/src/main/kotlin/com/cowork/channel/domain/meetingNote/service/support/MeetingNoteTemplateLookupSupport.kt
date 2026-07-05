package com.cowork.channel.domain.meetingNote.service.support

import com.cowork.channel.domain.meetingNote.entity.MeetingNoteTemplate
import com.cowork.channel.domain.meetingNote.entity.SectionType
import com.cowork.channel.domain.meetingNote.entity.TemplateSection
import com.cowork.channel.domain.meetingNote.repository.MeetingNoteTemplateRepository
import com.cowork.channel.domain.meetingNote.repository.TemplateSectionRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import team.themoment.sdk.exception.ExpectedException

@Component
class MeetingNoteTemplateLookupSupport(
    private val templateRepository: MeetingNoteTemplateRepository,
    private val sectionRepository: TemplateSectionRepository,
) {

    fun findTemplateOrThrow(templateId: Long): MeetingNoteTemplate =
        templateRepository.findById(templateId).orElseThrow {
            ExpectedException("템플릿을 찾을 수 없습니다.", HttpStatus.NOT_FOUND)
        }

    fun requireTemplateOwnership(template: MeetingNoteTemplate, channelId: Long) {
        if (template.channelId != channelId) {
            throw ExpectedException("템플릿을 찾을 수 없습니다.", HttpStatus.NOT_FOUND)
        }
    }

    fun findSectionOrThrow(sectionId: Long): TemplateSection = sectionRepository.findById(sectionId).orElseThrow {
        ExpectedException("섹션을 찾을 수 없습니다.", HttpStatus.NOT_FOUND)
    }

    fun requireSectionOwnership(section: TemplateSection, templateId: Long) {
        if (section.templateId != templateId) {
            throw ExpectedException("섹션을 찾을 수 없습니다.", HttpStatus.NOT_FOUND)
        }
    }

    fun parseSectionType(value: String): SectionType = try {
        SectionType.valueOf(value.uppercase())
    } catch (e: IllegalArgumentException) {
        throw ExpectedException("유효하지 않은 섹션 타입입니다.", HttpStatus.BAD_REQUEST)
    }
}
