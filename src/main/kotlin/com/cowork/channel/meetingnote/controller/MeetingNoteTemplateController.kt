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
}
