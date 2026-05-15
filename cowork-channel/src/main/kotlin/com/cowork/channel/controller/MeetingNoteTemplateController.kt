package com.cowork.channel.controller

import com.cowork.channel.dto.*
import com.cowork.channel.service.MeetingNoteTemplateService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@Tag(name = "회의록 템플릿", description = "회의록 템플릿 및 섹션 CRUD API")
@RestController
@RequestMapping("/channels/{channelId}/meeting-note-templates")
class MeetingNoteTemplateController(
    private val meetingNoteTemplateService: MeetingNoteTemplateService,
) {

    @Operation(summary = "템플릿 목록 조회", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
        ApiResponse(responseCode = "403", description = "채널 멤버가 아님"),
    )
    @GetMapping
    fun listTemplates(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable channelId: Long,
    ): ResponseEntity<List<MeetingNoteTemplateResponse>> =
        ResponseEntity.ok(meetingNoteTemplateService.listTemplates(userId, channelId))

    @Operation(summary = "템플릿 생성", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "생성 성공"),
        ApiResponse(responseCode = "403", description = "채널 멤버가 아님"),
    )
    @PostMapping
    fun createTemplate(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable channelId: Long,
        @RequestBody request: CreateMeetingNoteTemplateRequest,
    ): ResponseEntity<MeetingNoteTemplateResponse> =
        ResponseEntity.status(201).body(meetingNoteTemplateService.createTemplate(userId, channelId, request))

    @Operation(summary = "템플릿 상세 조회 (섹션 포함)", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
        ApiResponse(responseCode = "404", description = "템플릿 없음"),
    )
    @GetMapping("/{templateId}")
    fun getTemplate(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable channelId: Long,
        @PathVariable templateId: Long,
    ): ResponseEntity<MeetingNoteTemplateResponse> =
        ResponseEntity.ok(meetingNoteTemplateService.getTemplate(userId, channelId, templateId))

    @Operation(summary = "템플릿 이름 수정", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "수정 성공"),
        ApiResponse(responseCode = "403", description = "채널 멤버가 아님"),
    )
    @PatchMapping("/{templateId}")
    fun updateTemplate(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable channelId: Long,
        @PathVariable templateId: Long,
        @RequestBody request: UpdateMeetingNoteTemplateRequest,
    ): ResponseEntity<MeetingNoteTemplateResponse> =
        ResponseEntity.ok(meetingNoteTemplateService.updateTemplate(userId, channelId, templateId, request))

    @Operation(summary = "템플릿 삭제 (활성 템플릿 삭제 불가)", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "삭제 성공"),
        ApiResponse(responseCode = "400", description = "활성 템플릿 삭제 시도"),
        ApiResponse(responseCode = "403", description = "채널 멤버가 아님"),
    )
    @DeleteMapping("/{templateId}")
    fun deleteTemplate(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable channelId: Long,
        @PathVariable templateId: Long,
    ): ResponseEntity<Void> {
        meetingNoteTemplateService.deleteTemplate(userId, channelId, templateId)
        return ResponseEntity.noContent().build()
    }

    @Operation(summary = "템플릿 활성화 (기존 활성 자동 해제)", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "활성화 성공"),
        ApiResponse(responseCode = "403", description = "채널 멤버가 아님"),
    )
    @PatchMapping("/{templateId}/activate")
    fun activateTemplate(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable channelId: Long,
        @PathVariable templateId: Long,
    ): ResponseEntity<MeetingNoteTemplateResponse> =
        ResponseEntity.ok(meetingNoteTemplateService.activateTemplate(userId, channelId, templateId))

    @Operation(summary = "섹션 추가", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "추가 성공"),
        ApiResponse(responseCode = "403", description = "채널 멤버가 아님"),
    )
    @PostMapping("/{templateId}/sections")
    fun addSection(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable channelId: Long,
        @PathVariable templateId: Long,
        @RequestBody request: CreateTemplateSectionRequest,
    ): ResponseEntity<TemplateSectionResponse> =
        ResponseEntity.status(201).body(meetingNoteTemplateService.addSection(userId, channelId, templateId, request))

    @Operation(summary = "섹션 수정", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "수정 성공"),
        ApiResponse(responseCode = "403", description = "채널 멤버가 아님"),
    )
    @PatchMapping("/{templateId}/sections/{sectionId}")
    fun updateSection(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable channelId: Long,
        @PathVariable templateId: Long,
        @PathVariable sectionId: Long,
        @RequestBody request: UpdateTemplateSectionRequest,
    ): ResponseEntity<TemplateSectionResponse> =
        ResponseEntity.ok(meetingNoteTemplateService.updateSection(userId, channelId, templateId, sectionId, request))

    @Operation(summary = "섹션 삭제", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "삭제 성공"),
        ApiResponse(responseCode = "403", description = "채널 멤버가 아님"),
    )
    @DeleteMapping("/{templateId}/sections/{sectionId}")
    fun deleteSection(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable channelId: Long,
        @PathVariable templateId: Long,
        @PathVariable sectionId: Long,
    ): ResponseEntity<Void> {
        meetingNoteTemplateService.deleteSection(userId, channelId, templateId, sectionId)
        return ResponseEntity.noContent().build()
    }
}
