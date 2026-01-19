package com.cowork.channel.meetingnote.service

import com.cowork.channel.domain.Channel
import com.cowork.channel.domain.ChannelViewType
import com.cowork.channel.meetingnote.domain.MeetingNoteTemplate
import com.cowork.channel.meetingnote.domain.TemplateSection
import com.cowork.channel.meetingnote.dto.CreateSectionRequest
import com.cowork.channel.meetingnote.dto.CreateTemplateRequest
import com.cowork.channel.meetingnote.dto.TemplateResponse
import com.cowork.channel.meetingnote.dto.UpdateSectionRequest
import com.cowork.channel.meetingnote.repository.MeetingNoteTemplateRepository
import com.cowork.channel.meetingnote.repository.TemplateSectionRepository
import com.cowork.channel.service.ChannelAccessChecker
import com.cowork.global.error.BusinessException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class MeetingNoteTemplateService(
    private val templateRepository: MeetingNoteTemplateRepository,
    private val sectionRepository: TemplateSectionRepository,
    private val channelAccessChecker: ChannelAccessChecker,
) {

    @Transactional(readOnly = true)
    fun getTemplates(channelId: Long, userId: Long): List<TemplateResponse> {
        requireNoteChannel(channelId, userId)
        val templates = templateRepository.findAllByChannelIdOrderByIdAsc(channelId)
        val sections = sectionRepository.findAllByTemplateIdInOrderByPositionAscIdAsc(templates.map { it.id })
            .groupBy { it.templateId }
        return templates.map { TemplateResponse.of(it, sections[it.id].orEmpty()) }
    }

    @Transactional(readOnly = true)
    fun getTemplate(channelId: Long, templateId: Long, userId: Long): TemplateResponse {
        requireNoteChannel(channelId, userId)
        return toResponse(findTemplate(channelId, templateId))
    }

    @Transactional
    fun create(channelId: Long, userId: Long, request: CreateTemplateRequest): TemplateResponse {
        requireNoteChannel(channelId, userId)
        val isFirst = templateRepository.findFirstByChannelIdAndIsActiveTrue(channelId) == null
        val template = templateRepository.save(
            MeetingNoteTemplate(channelId = channelId, name = request.name, createdBy = userId, isActive = isFirst),
        )
        request.sections.forEachIndexed { index, section -> saveSection(template.id, section, index) }
        return toResponse(template)
    }

    @Transactional
    fun rename(channelId: Long, templateId: Long, userId: Long, name: String): TemplateResponse {
        val template = requireEditableTemplate(channelId, templateId, userId)
        template.rename(name)
        return toResponse(template)
    }

    @Transactional
    fun delete(channelId: Long, templateId: Long, userId: Long) {
        val template = requireEditableTemplate(channelId, templateId, userId)
        if (template.isActive) {
            throw BusinessException(HttpStatus.BAD_REQUEST, "사용 중인 템플릿은 삭제할 수 없습니다. 다른 템플릿을 먼저 활성화해 주세요.")
        }
        sectionRepository.deleteAllByTemplateIdIn(listOf(template.id))
        templateRepository.delete(template)
    }

    @Transactional
    fun activate(channelId: Long, templateId: Long, userId: Long): TemplateResponse {
        val channel = requireNoteChannel(channelId, userId)
        if (!channelAccessChecker.canManage(channel, userId)) {
            throw BusinessException(HttpStatus.FORBIDDEN, "채널 관리자만 템플릿을 활성화할 수 있습니다.")
        }
        val template = findTemplate(channelId, templateId)
        templateRepository.findFirstByChannelIdAndIsActiveTrue(channelId)
            ?.takeIf { it.id != template.id }
            ?.deactivate()
        template.activate()
        return toResponse(template)
    }

    @Transactional
    fun addSection(channelId: Long, templateId: Long, userId: Long, request: CreateSectionRequest): TemplateResponse {
        val template = requireEditableTemplate(channelId, templateId, userId)
        saveSection(template.id, request, sectionRepository.findMaxPosition(template.id) + 1)
        return toResponse(template)
    }

    @Transactional
    fun updateSection(channelId: Long, templateId: Long, sectionId: Long, userId: Long, request: UpdateSectionRequest): TemplateResponse {
        val template = requireEditableTemplate(channelId, templateId, userId)
        val section = findSection(template.id, sectionId)
        section.update(request.title, request.type, request.placeholder, request.required, request.position)
        return toResponse(template)
    }

    @Transactional
    fun deleteSection(channelId: Long, templateId: Long, sectionId: Long, userId: Long): TemplateResponse {
        val template = requireEditableTemplate(channelId, templateId, userId)
        sectionRepository.delete(findSection(template.id, sectionId))
        return toResponse(template)
    }

    fun requireNoteChannel(channelId: Long, userId: Long): Channel {
        val channel = channelAccessChecker.requireReadable(channelId, userId)
        channelAccessChecker.requireViewType(channel, ChannelViewType.MEETING_NOTE)
        return channel
    }

    private fun requireEditableTemplate(channelId: Long, templateId: Long, userId: Long): MeetingNoteTemplate {
        val channel = requireNoteChannel(channelId, userId)
        val template = findTemplate(channelId, templateId)
        if (template.createdBy != userId && !channelAccessChecker.canManage(channel, userId)) {
            throw BusinessException(HttpStatus.FORBIDDEN, "템플릿을 만든 사용자나 채널 관리자만 수정할 수 있습니다.")
        }
        return template
    }

    private fun saveSection(templateId: Long, request: CreateSectionRequest, position: Int) {
        sectionRepository.save(
            TemplateSection(
                templateId = templateId,
                title = request.title,
                type = request.type,
                placeholder = request.placeholder,
                required = request.required,
                position = position,
            ),
        )
    }

    private fun findTemplate(channelId: Long, templateId: Long): MeetingNoteTemplate =
        templateRepository.findByIdAndChannelId(templateId, channelId)
            ?: throw BusinessException(HttpStatus.NOT_FOUND, "템플릿을 찾을 수 없습니다.")

    private fun findSection(templateId: Long, sectionId: Long): TemplateSection =
        sectionRepository.findByIdAndTemplateId(sectionId, templateId)
            ?: throw BusinessException(HttpStatus.NOT_FOUND, "섹션을 찾을 수 없습니다.")

    private fun toResponse(template: MeetingNoteTemplate) =
        TemplateResponse.of(template, sectionRepository.findAllByTemplateIdOrderByPositionAscIdAsc(template.id))
}
