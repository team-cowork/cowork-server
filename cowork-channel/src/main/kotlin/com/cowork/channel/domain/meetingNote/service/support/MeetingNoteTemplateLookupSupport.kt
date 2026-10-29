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

    // 회의록 content가 섹션 제목을 키로 쓰므로, 같은 템플릿 안에서 제목이 겹치면 어느 섹션의 내용인지 구분할 수 없다.
    // DB collation(utf8mb4_unicode_ci)이 대소문자와 끝 공백을 무시하므로, 수정 시에는 자기 자신을 빼고 비교한다.
    fun requireUniqueSectionTitle(templateId: Long, title: String, excludeSectionId: Long? = null) {
        val exists = if (excludeSectionId == null) {
            sectionRepository.existsByTemplateIdAndTitle(templateId, title)
        } else {
            sectionRepository.existsByTemplateIdAndTitleAndIdNot(templateId, title, excludeSectionId)
        }
        if (exists) {
            throw ExpectedException("같은 제목의 섹션이 이미 있습니다.", HttpStatus.CONFLICT)
        }
    }

    fun parseSectionType(value: String): SectionType = try {
        SectionType.valueOf(value.uppercase())
    } catch (e: IllegalArgumentException) {
        throw ExpectedException("유효하지 않은 섹션 타입입니다.", HttpStatus.BAD_REQUEST)
    }
}
