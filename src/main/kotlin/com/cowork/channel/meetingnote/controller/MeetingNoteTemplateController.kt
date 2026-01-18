package com.cowork.channel.meetingnote.controller

import com.cowork.channel.meetingnote.dto.CreateSectionRequest
import com.cowork.channel.meetingnote.dto.CreateTemplateRequest
import com.cowork.channel.meetingnote.dto.TemplateResponse
import com.cowork.channel.meetingnote.dto.UpdateSectionRequest
import com.cowork.channel.meetingnote.dto.UpdateTemplateRequest
import com.cowork.channel.meetingnote.service.MeetingNoteTemplateService
import com.cowork.global.security.AuthUser
import com.cowork.global.security.CurrentUser
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/channels/{channelId}/meeting-note-templates")
class MeetingNoteTemplateController(
    private val templateService: MeetingNoteTemplateService,
) {

    @GetMapping
    fun getTemplates(@CurrentUser user: AuthUser, @PathVariable channelId: Long): List<TemplateResponse> =
        templateService.getTemplates(channelId, user.id)

    @PostMapping
    fun create(
        @CurrentUser user: AuthUser,
        @PathVariable channelId: Long,
        @RequestBody request: CreateTemplateRequest,
    ): TemplateResponse = templateService.create(channelId, user.id, request)

    @GetMapping("/{templateId}")
    fun getTemplate(
        @CurrentUser user: AuthUser,
        @PathVariable channelId: Long,
        @PathVariable templateId: Long,
    ): TemplateResponse = templateService.getTemplate(channelId, templateId, user.id)

    @PatchMapping("/{templateId}")
    fun rename(
        @CurrentUser user: AuthUser,
        @PathVariable channelId: Long,
        @PathVariable templateId: Long,
        @RequestBody request: UpdateTemplateRequest,
    ): TemplateResponse = templateService.rename(channelId, templateId, user.id, request.name)

    @DeleteMapping("/{templateId}")
    fun delete(
        @CurrentUser user: AuthUser,
        @PathVariable channelId: Long,
        @PathVariable templateId: Long,
    ) = templateService.delete(channelId, templateId, user.id)

    @PatchMapping("/{templateId}/activate")
    fun activate(
        @CurrentUser user: AuthUser,
        @PathVariable channelId: Long,
        @PathVariable templateId: Long,
    ): TemplateResponse = templateService.activate(channelId, templateId, user.id)

    @PostMapping("/{templateId}/sections")
    fun addSection(
        @CurrentUser user: AuthUser,
        @PathVariable channelId: Long,
        @PathVariable templateId: Long,
        @RequestBody request: CreateSectionRequest,
    ): TemplateResponse = templateService.addSection(channelId, templateId, user.id, request)

    @PatchMapping("/{templateId}/sections/{sectionId}")
    fun updateSection(
        @CurrentUser user: AuthUser,
        @PathVariable channelId: Long,
        @PathVariable templateId: Long,
        @PathVariable sectionId: Long,
        @RequestBody request: UpdateSectionRequest,
    ): TemplateResponse = templateService.updateSection(channelId, templateId, sectionId, user.id, request)
}
