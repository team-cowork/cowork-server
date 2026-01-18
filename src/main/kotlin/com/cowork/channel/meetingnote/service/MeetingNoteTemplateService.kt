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

    @Transactional
    fun getTemplates(channelId: Long, userId: Long): List<TemplateResponse> {
        requireNoteChannel(channelId, userId)
        val templates = templateRepository.findAllByChannelIdOrderByIdAsc(channelId)
        val sections = sectionRepository.findAllByTemplateIdInOrderByPositionAscIdAsc(templates.map { it.id })
            .groupBy { it.templateId }
        return templates.map { TemplateResponse.of(it, sections[it.id].orEmpty()) }
    }

    fun requireNoteChannel(channelId: Long, userId: Long): Channel {
        val channel = channelAccessChecker.requireReadable(channelId, userId)
        channelAccessChecker.requireViewType(channel, ChannelViewType.MEETING_NOTE)
        return channel
    }
}
