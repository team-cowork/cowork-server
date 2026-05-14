package com.cowork.channel.service

import com.cowork.channel.domain.Channel
import com.cowork.channel.domain.MeetingNoteTemplate
import com.cowork.channel.domain.SectionType
import com.cowork.channel.domain.TemplateSection
import com.cowork.channel.dto.*
import com.cowork.channel.repository.ChannelMemberRepository
import com.cowork.channel.repository.ChannelRepository
import com.cowork.channel.repository.MeetingNoteTemplateRepository
import com.cowork.channel.repository.TemplateSectionRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.themoment.sdk.exception.ExpectedException

@Service
@Transactional(readOnly = true)
class MeetingNoteTemplateService(
    private val templateRepository: MeetingNoteTemplateRepository,
    private val sectionRepository: TemplateSectionRepository,
    private val channelRepository: ChannelRepository,
    private val channelMemberRepository: ChannelMemberRepository,
) {

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

    private fun requireChannelExists(channelId: Long) {
        if (!channelRepository.existsById(channelId)) {
            throw ExpectedException("채널을 찾을 수 없습니다. id=$channelId", HttpStatus.NOT_FOUND)
        }
    }

    private fun findTemplateOrThrow(templateId: Long): MeetingNoteTemplate =
        templateRepository.findById(templateId).orElseThrow {
            ExpectedException("템플릿을 찾을 수 없습니다. id=$templateId", HttpStatus.NOT_FOUND)
        }

    private fun findSectionOrThrow(sectionId: Long): TemplateSection =
        sectionRepository.findById(sectionId).orElseThrow {
            ExpectedException("섹션을 찾을 수 없습니다. id=$sectionId", HttpStatus.NOT_FOUND)
        }

    private fun requireChannelMember(channelId: Long, userId: Long) {
        if (!channelMemberRepository.existsByChannelIdAndUserId(channelId, userId)) {
            throw ExpectedException("채널 멤버만 접근할 수 있습니다.", HttpStatus.FORBIDDEN)
        }
    }

    private fun requireTemplateOwnership(template: MeetingNoteTemplate, channelId: Long) {
        if (template.channelId != channelId) {
            throw ExpectedException("템플릿을 찾을 수 없습니다.", HttpStatus.NOT_FOUND)
        }
    }

    private fun requireSectionOwnership(section: TemplateSection, templateId: Long) {
        if (section.templateId != templateId) {
            throw ExpectedException("섹션을 찾을 수 없습니다.", HttpStatus.NOT_FOUND)
        }
    }

    private fun parseSectionType(value: String): SectionType = try {
        SectionType.valueOf(value.uppercase())
    } catch (e: IllegalArgumentException) {
        throw ExpectedException("유효하지 않은 섹션 타입입니다. type=$value", HttpStatus.BAD_REQUEST)
    }

    private fun toResponse(template: MeetingNoteTemplate): MeetingNoteTemplateResponse {
        val sections = sectionRepository.findAllByTemplateIdOrderByIdAsc(template.id)
            .map { TemplateSectionResponse.of(it) }
        return MeetingNoteTemplateResponse.of(template, sections)
    }

    fun listTemplates(userId: Long, channelId: Long): List<MeetingNoteTemplateResponse> {
        requireChannelExists(channelId)
        requireChannelMember(channelId, userId)
        return templateRepository.findAllByChannelIdOrderByIdAsc(channelId).map { toResponse(it) }
    }

    fun getTemplate(userId: Long, channelId: Long, templateId: Long): MeetingNoteTemplateResponse {
        requireChannelExists(channelId)
        requireChannelMember(channelId, userId)
        val template = findTemplateOrThrow(templateId)
        requireTemplateOwnership(template, channelId)
        return toResponse(template)
    }

    @Transactional
    fun createTemplate(userId: Long, channelId: Long, request: CreateMeetingNoteTemplateRequest): MeetingNoteTemplateResponse {
        requireChannelExists(channelId)
        requireChannelMember(channelId, userId)
        val template = templateRepository.save(
            MeetingNoteTemplate(
                channelId = channelId,
                name = request.name,
                isActive = false,
                createdBy = userId,
            )
        )
        return MeetingNoteTemplateResponse.of(template, emptyList())
    }

    @Transactional
    fun updateTemplate(userId: Long, channelId: Long, templateId: Long, request: UpdateMeetingNoteTemplateRequest): MeetingNoteTemplateResponse {
        requireChannelExists(channelId)
        requireChannelMember(channelId, userId)
        val template = findTemplateOrThrow(templateId)
        requireTemplateOwnership(template, channelId)
        template.updateName(request.name)
        return toResponse(template)
    }

    @Transactional
    fun deleteTemplate(userId: Long, channelId: Long, templateId: Long) {
        requireChannelExists(channelId)
        requireChannelMember(channelId, userId)
        val template = findTemplateOrThrow(templateId)
        requireTemplateOwnership(template, channelId)
        if (template.isActive) {
            throw ExpectedException("활성 템플릿은 삭제할 수 없습니다. 다른 템플릿을 활성화한 후 삭제해 주세요.", HttpStatus.BAD_REQUEST)
        }
        sectionRepository.deleteAll(sectionRepository.findAllByTemplateIdOrderByIdAsc(templateId))
        templateRepository.delete(template)
    }

    @Transactional
    fun activateTemplate(userId: Long, channelId: Long, templateId: Long): MeetingNoteTemplateResponse {
        requireChannelExists(channelId)
        requireChannelMember(channelId, userId)
        val template = findTemplateOrThrow(templateId)
        requireTemplateOwnership(template, channelId)
        templateRepository.findByChannelIdAndIsActiveTrue(channelId)
            ?.takeIf { it.id != templateId }
            ?.deactivate()
        template.activate()
        return toResponse(template)
    }

    @Transactional
    fun addSection(userId: Long, channelId: Long, templateId: Long, request: CreateTemplateSectionRequest): TemplateSectionResponse {
        requireChannelExists(channelId)
        requireChannelMember(channelId, userId)
        val template = findTemplateOrThrow(templateId)
        requireTemplateOwnership(template, channelId)
        val section = sectionRepository.save(
            TemplateSection(
                templateId = templateId,
                title = request.title,
                type = parseSectionType(request.type),
                placeholder = request.placeholder,
                isRequired = request.isRequired,
            )
        )
        return TemplateSectionResponse.of(section)
    }

    @Transactional
    fun updateSection(userId: Long, channelId: Long, templateId: Long, sectionId: Long, request: UpdateTemplateSectionRequest): TemplateSectionResponse {
        requireChannelExists(channelId)
        requireChannelMember(channelId, userId)
        val template = findTemplateOrThrow(templateId)
        requireTemplateOwnership(template, channelId)
        val section = findSectionOrThrow(sectionId)
        requireSectionOwnership(section, templateId)
        section.update(
            title = request.title,
            type = request.type?.let { parseSectionType(it) },
            placeholder = request.placeholder,
            isRequired = request.isRequired,
        )
        return TemplateSectionResponse.of(section)
    }

    @Transactional
    fun deleteSection(userId: Long, channelId: Long, templateId: Long, sectionId: Long) {
        requireChannelExists(channelId)
        requireChannelMember(channelId, userId)
        val template = findTemplateOrThrow(templateId)
        requireTemplateOwnership(template, channelId)
        val section = findSectionOrThrow(sectionId)
        requireSectionOwnership(section, templateId)
        sectionRepository.delete(section)
    }

    @Transactional
    fun createDefaultTemplate(channel: Channel) {
        val template = templateRepository.save(
            MeetingNoteTemplate(
                channelId = channel.id,
                name = "${channel.name} - 회의록 템플릿",
                isActive = true,
                createdBy = channel.createdBy,
            )
        )
        defaultSections.forEach { def ->
            sectionRepository.save(
                TemplateSection(
                    templateId = template.id,
                    title = def.title,
                    type = def.type,
                    placeholder = def.placeholder,
                    isRequired = def.isRequired,
                )
            )
        }
    }
}
