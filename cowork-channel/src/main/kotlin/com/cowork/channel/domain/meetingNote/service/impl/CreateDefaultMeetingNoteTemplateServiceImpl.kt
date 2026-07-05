package com.cowork.channel.domain.meetingNote.service.impl

import com.cowork.channel.domain.channel.entity.Channel
import com.cowork.channel.domain.meetingNote.entity.MeetingNoteTemplate
import com.cowork.channel.domain.meetingNote.entity.SectionType
import com.cowork.channel.domain.meetingNote.entity.TemplateSection
import com.cowork.channel.domain.meetingNote.repository.MeetingNoteTemplateRepository
import com.cowork.channel.domain.meetingNote.repository.TemplateSectionRepository
import com.cowork.channel.domain.meetingNote.service.CreateDefaultMeetingNoteTemplateService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional
class CreateDefaultMeetingNoteTemplateServiceImpl(
    private val templateRepository: MeetingNoteTemplateRepository,
    private val sectionRepository: TemplateSectionRepository,
) : CreateDefaultMeetingNoteTemplateService {

    private data class DefaultSectionDef(
        val title: String,
        val type: SectionType,
        val placeholder: String,
        val isRequired: Boolean,
    )

    private val defaultSections = listOf(
        DefaultSectionDef("회의 제목", SectionType.TEXT, "회의 제목을 입력하세요", true),
        DefaultSectionDef("일시 / 장소", SectionType.DATETIME, "2024-01-01 14:00 / 대회의실", true),
        DefaultSectionDef("참석자", SectionType.USER_LIST, "홍길동, 김철수, ...", true),
        DefaultSectionDef("안건", SectionType.MARKDOWN, "논의할 주제를 입력하세요", true),
        DefaultSectionDef("결정사항", SectionType.MARKDOWN, "합의된 내용을 입력하세요", false),
        DefaultSectionDef("다음 회의 일정", SectionType.DATETIME, "다음 회의 일정을 입력하세요", false),
    )

    override fun createDefaultTemplate(channel: Channel) {
        val template = templateRepository.save(
            MeetingNoteTemplate(
                channelId = channel.id,
                name = "${channel.name} - 회의록 템플릿",
                isActive = true,
                createdBy = channel.createdBy,
            ),
        )
        sectionRepository.saveAll(
            defaultSections.map { def ->
                TemplateSection(
                    templateId = template.id,
                    title = def.title,
                    type = def.type,
                    placeholder = def.placeholder,
                    isRequired = def.isRequired,
                )
            },
        )
    }
}
