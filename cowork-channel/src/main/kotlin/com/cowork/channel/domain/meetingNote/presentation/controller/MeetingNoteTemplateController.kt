package com.cowork.channel.domain.meetingNote.presentation.controller

import com.cowork.channel.domain.meetingNote.presentation.data.request.CreateMeetingNoteTemplateRequest
import com.cowork.channel.domain.meetingNote.presentation.data.request.CreateTemplateSectionRequest
import com.cowork.channel.domain.meetingNote.presentation.data.request.UpdateMeetingNoteTemplateRequest
import com.cowork.channel.domain.meetingNote.presentation.data.request.UpdateTemplateSectionRequest
import com.cowork.channel.domain.meetingNote.presentation.data.response.MeetingNoteTemplateResponse
import com.cowork.channel.domain.meetingNote.presentation.data.response.TemplateSectionResponse
import com.cowork.channel.domain.meetingNote.service.ActivateMeetingNoteTemplateService
import com.cowork.channel.domain.meetingNote.service.AddTemplateSectionService
import com.cowork.channel.domain.meetingNote.service.CreateMeetingNoteTemplateService
import com.cowork.channel.domain.meetingNote.service.DeleteMeetingNoteTemplateService
import com.cowork.channel.domain.meetingNote.service.DeleteTemplateSectionService
import com.cowork.channel.domain.meetingNote.service.GetMeetingNoteTemplateService
import com.cowork.channel.domain.meetingNote.service.ListMeetingNoteTemplatesService
import com.cowork.channel.domain.meetingNote.service.UpdateMeetingNoteTemplateService
import com.cowork.channel.domain.meetingNote.service.UpdateTemplateSectionService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.media.SchemaProperty
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import team.themoment.sdk.response.CommonApiResponse

@Tag(name = "회의록 템플릿", description = "회의록 양식과 섹션을 관리합니다.")
@ApiResponses(
    ApiResponse(
        responseCode = "429",
        description = "요청 한도 초과",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = CommonApiResponse::class))],
    ),
    ApiResponse(
        responseCode = "503",
        description = "권한 정보 동기화 중",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = CommonApiResponse::class))],
    ),
    ApiResponse(
        responseCode = "401",
        description = "액세스 토큰이 없거나 유효하지 않음",
        content = [
            Content(
                mediaType = "application/json",
                schema = Schema(implementation = CommonApiResponse::class),
                examples = [
                    ExampleObject(
                        value = """{"status":"401 UNAUTHORIZED","code":401,"message":"Unauthorized"}""",
                    ),
                ],
            ),
        ],
    ),
    ApiResponse(
        responseCode = "default",
        description = "기타 오류",
        content = [Content(schema = Schema(implementation = CommonApiResponse::class))],
    ),
)
@RestController
@RequestMapping("/channels/{channelId}/meeting-note-templates")
class MeetingNoteTemplateController(
    private val listMeetingNoteTemplatesService: ListMeetingNoteTemplatesService,
    private val createMeetingNoteTemplateService: CreateMeetingNoteTemplateService,
    private val getMeetingNoteTemplateService: GetMeetingNoteTemplateService,
    private val updateMeetingNoteTemplateService: UpdateMeetingNoteTemplateService,
    private val deleteMeetingNoteTemplateService: DeleteMeetingNoteTemplateService,
    private val activateMeetingNoteTemplateService: ActivateMeetingNoteTemplateService,
    private val addTemplateSectionService: AddTemplateSectionService,
    private val updateTemplateSectionService: UpdateTemplateSectionService,
    private val deleteTemplateSectionService: DeleteTemplateSectionService,
) {

    @Operation(summary = "템플릿 목록 조회", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(
            responseCode = "400",
            description = "요청 값이 올바르지 않거나 DM 채널",
            content = [
                Content(
                    mediaType = "application/json",
                    schema = Schema(implementation = CommonApiResponse::class),
                ),
            ],
        ),
        ApiResponse(
            responseCode = "404",
            description = "채널 없음",
            content = [
                Content(
                    mediaType = "application/json",
                    schema = Schema(implementation = CommonApiResponse::class),
                ),
            ],
        ),
        ApiResponse(responseCode = "200", description = "조회 성공"),
        ApiResponse(
            responseCode = "403",
            description = "채널 접근 권한 없음",
            content = [
                Content(
                    mediaType = "application/json",
                    schema = Schema(implementation = CommonApiResponse::class),
                    examples = [
                        ExampleObject(
                            value = """{"status":"403 FORBIDDEN","code":403,"message":"요청을 수행할 권한이 없습니다."}""",
                        ),
                    ],
                ),
            ],
        ),
    )
    @GetMapping
    fun listTemplates(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable channelId: Long,
    ): List<MeetingNoteTemplateResponse> = listMeetingNoteTemplatesService.listTemplates(userId, channelId)

    @Operation(summary = "템플릿 생성", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(
            responseCode = "400",
            description = "요청 값이 올바르지 않거나 DM 채널",
            content = [
                Content(
                    mediaType = "application/json",
                    schema = Schema(implementation = CommonApiResponse::class),
                ),
            ],
        ),
        ApiResponse(
            responseCode = "409",
            description = "보관된 채널은 변경할 수 없음",
            content = [
                Content(
                    mediaType = "application/json",
                    schema = Schema(implementation = CommonApiResponse::class),
                ),
            ],
        ),
        ApiResponse(
            responseCode = "404",
            description = "채널 없음",
            content = [
                Content(
                    mediaType = "application/json",
                    schema = Schema(implementation = CommonApiResponse::class),
                ),
            ],
        ),
        ApiResponse(
            responseCode = "201",
            description = "생성 성공",
            content = [
                Content(
                    mediaType = "application/json",
                    schema = Schema(type = "object", requiredProperties = ["status", "code", "message", "data"]),
                    schemaProperties = [
                        SchemaProperty(
                            name = "status",
                            schema = Schema(implementation = HttpStatus::class, example = "201 CREATED"),
                        ), SchemaProperty(
                            name = "code",
                            schema = Schema(type = "integer", example = "201"),
                        ), SchemaProperty(name = "message", schema = Schema(type = "string", example = "Created")),

                        SchemaProperty(
                            name = "data",
                            schema = Schema(implementation = MeetingNoteTemplateResponse::class),
                        ),
                    ],
                ),
            ],
        ),
        ApiResponse(
            responseCode = "403",
            description = "채널 접근 권한 없음",
            content = [
                Content(
                    mediaType = "application/json",
                    schema = Schema(implementation = CommonApiResponse::class),
                    examples = [
                        ExampleObject(
                            value = """{"status":"403 FORBIDDEN","code":403,"message":"요청을 수행할 권한이 없습니다."}""",
                        ),
                    ],
                ),
            ],
        ),
    )
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun createTemplate(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable channelId: Long,
        @RequestBody request: CreateMeetingNoteTemplateRequest,
    ): MeetingNoteTemplateResponse = createMeetingNoteTemplateService.createTemplate(userId, channelId, request)

    @Operation(
        summary = "템플릿 상세 조회",
        description = "템플릿에 등록된 섹션을 함께 반환합니다.",
        security = [SecurityRequirement(name = "BearerAuth")],
    )
    @ApiResponses(
        ApiResponse(
            responseCode = "400",
            description = "요청 값이 올바르지 않거나 DM 채널",
            content = [
                Content(
                    mediaType = "application/json",
                    schema = Schema(implementation = CommonApiResponse::class),
                ),
            ],
        ),
        ApiResponse(
            responseCode = "403",
            description = "채널 접근 권한 없음",
            content = [
                Content(
                    mediaType = "application/json",
                    schema = Schema(implementation = CommonApiResponse::class),
                ),
            ],
        ),
        ApiResponse(responseCode = "200", description = "조회 성공"),
        ApiResponse(
            responseCode = "404",
            description = "채널 또는 템플릿 없음",
            content = [
                Content(
                    mediaType = "application/json",
                    schema = Schema(implementation = CommonApiResponse::class),
                    examples = [
                        ExampleObject(
                            value = """{"status":"404 NOT_FOUND","code":404,"message":"요청한 대상을 찾을 수 없습니다."}""",
                        ),
                    ],
                ),
            ],
        ),
    )
    @GetMapping("/{templateId}")
    fun getTemplate(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable channelId: Long,
        @PathVariable templateId: Long,
    ): MeetingNoteTemplateResponse = getMeetingNoteTemplateService.getTemplate(userId, channelId, templateId)

    @Operation(summary = "템플릿 이름 수정", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(
            responseCode = "400",
            description = "요청 값이 올바르지 않거나 DM 채널",
            content = [
                Content(
                    mediaType = "application/json",
                    schema = Schema(implementation = CommonApiResponse::class),
                ),
            ],
        ),
        ApiResponse(
            responseCode = "409",
            description = "보관된 채널은 변경할 수 없음",
            content = [
                Content(
                    mediaType = "application/json",
                    schema = Schema(implementation = CommonApiResponse::class),
                ),
            ],
        ),
        ApiResponse(
            responseCode = "404",
            description = "채널 또는 템플릿 없음",
            content = [
                Content(
                    mediaType = "application/json",
                    schema = Schema(implementation = CommonApiResponse::class),
                ),
            ],
        ),
        ApiResponse(responseCode = "200", description = "수정 성공"),
        ApiResponse(
            responseCode = "403",
            description = "채널 접근 권한 없음",
            content = [
                Content(
                    mediaType = "application/json",
                    schema = Schema(implementation = CommonApiResponse::class),
                    examples = [
                        ExampleObject(
                            value = """{"status":"403 FORBIDDEN","code":403,"message":"요청을 수행할 권한이 없습니다."}""",
                        ),
                    ],
                ),
            ],
        ),
    )
    @PatchMapping("/{templateId}")
    fun updateTemplate(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable channelId: Long,
        @PathVariable templateId: Long,
        @RequestBody request: UpdateMeetingNoteTemplateRequest,
    ): MeetingNoteTemplateResponse =
        updateMeetingNoteTemplateService.updateTemplate(userId, channelId, templateId, request)

    @Operation(
        summary = "템플릿 삭제",
        description = "활성 템플릿이나 회의록 작성에 사용한 템플릿은 삭제할 수 없습니다.",
        security = [SecurityRequirement(name = "BearerAuth")],
    )
    @ApiResponses(
        ApiResponse(
            responseCode = "409",
            description = "보관된 채널은 변경할 수 없음",
            content = [
                Content(
                    mediaType = "application/json",
                    schema = Schema(implementation = CommonApiResponse::class),
                ),
            ],
        ),
        ApiResponse(
            responseCode = "404",
            description = "채널 또는 템플릿 없음",
            content = [
                Content(
                    mediaType = "application/json",
                    schema = Schema(implementation = CommonApiResponse::class),
                ),
            ],
        ),
        ApiResponse(responseCode = "204", description = "삭제 성공", content = [Content()]),
        ApiResponse(
            responseCode = "400",
            description = "DM 채널 또는 활성/사용 중인 템플릿 삭제 시도",
            content = [
                Content(
                    mediaType = "application/json",
                    schema = Schema(implementation = CommonApiResponse::class),
                    examples = [
                        ExampleObject(
                            value = """{"status":"400 BAD_REQUEST","code":400,"message":"요청 값이 올바르지 않습니다."}""",
                        ),
                    ],
                ),
            ],
        ),
        ApiResponse(
            responseCode = "403",
            description = "채널 접근 권한 없음",
            content = [
                Content(
                    mediaType = "application/json",
                    schema = Schema(implementation = CommonApiResponse::class),
                    examples = [
                        ExampleObject(
                            value = """{"status":"403 FORBIDDEN","code":403,"message":"요청을 수행할 권한이 없습니다."}""",
                        ),
                    ],
                ),
            ],
        ),
    )
    @DeleteMapping("/{templateId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteTemplate(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable channelId: Long,
        @PathVariable templateId: Long,
    ) {
        deleteMeetingNoteTemplateService.deleteTemplate(userId, channelId, templateId)
    }

    @Operation(
        summary = "템플릿 활성화",
        description = "기존 활성 템플릿은 자동으로 비활성화합니다.",
        security = [SecurityRequirement(name = "BearerAuth")],
    )
    @ApiResponses(
        ApiResponse(
            responseCode = "400",
            description = "요청 값이 올바르지 않거나 DM 채널",
            content = [
                Content(
                    mediaType = "application/json",
                    schema = Schema(implementation = CommonApiResponse::class),
                ),
            ],
        ),
        ApiResponse(
            responseCode = "409",
            description = "보관된 채널은 변경할 수 없음",
            content = [
                Content(
                    mediaType = "application/json",
                    schema = Schema(implementation = CommonApiResponse::class),
                ),
            ],
        ),
        ApiResponse(
            responseCode = "404",
            description = "채널 또는 템플릿 없음",
            content = [
                Content(
                    mediaType = "application/json",
                    schema = Schema(implementation = CommonApiResponse::class),
                ),
            ],
        ),
        ApiResponse(responseCode = "200", description = "활성화 성공"),
        ApiResponse(
            responseCode = "403",
            description = "채널 접근 권한 없음",
            content = [
                Content(
                    mediaType = "application/json",
                    schema = Schema(implementation = CommonApiResponse::class),
                    examples = [
                        ExampleObject(
                            value = """{"status":"403 FORBIDDEN","code":403,"message":"요청을 수행할 권한이 없습니다."}""",
                        ),
                    ],
                ),
            ],
        ),
    )
    @PatchMapping("/{templateId}/activate")
    fun activateTemplate(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable channelId: Long,
        @PathVariable templateId: Long,
    ): MeetingNoteTemplateResponse = activateMeetingNoteTemplateService.activateTemplate(userId, channelId, templateId)

    @Operation(summary = "섹션 추가", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(
            responseCode = "400",
            description = "요청 값이 올바르지 않거나 지원하지 않는 섹션 유형 또는 DM 채널",
            content = [
                Content(
                    mediaType = "application/json",
                    schema = Schema(implementation = CommonApiResponse::class),
                ),
            ],
        ),
        ApiResponse(
            responseCode = "409",
            description = "보관된 채널 또는 같은 제목의 섹션이 이미 있음",
            content = [
                Content(
                    mediaType = "application/json",
                    schema = Schema(implementation = CommonApiResponse::class),
                ),
            ],
        ),
        ApiResponse(
            responseCode = "404",
            description = "채널 또는 템플릿 없음",
            content = [
                Content(
                    mediaType = "application/json",
                    schema = Schema(implementation = CommonApiResponse::class),
                ),
            ],
        ),
        ApiResponse(
            responseCode = "201",
            description = "추가 성공",
            content = [
                Content(
                    mediaType = "application/json",
                    schema = Schema(type = "object", requiredProperties = ["status", "code", "message", "data"]),
                    schemaProperties = [
                        SchemaProperty(
                            name = "status",
                            schema = Schema(implementation = HttpStatus::class, example = "201 CREATED"),
                        ), SchemaProperty(
                            name = "code",
                            schema = Schema(type = "integer", example = "201"),
                        ), SchemaProperty(name = "message", schema = Schema(type = "string", example = "Created")),

                        SchemaProperty(
                            name = "data",
                            schema = Schema(implementation = TemplateSectionResponse::class),
                        ),
                    ],
                ),
            ],
        ),
        ApiResponse(
            responseCode = "403",
            description = "채널 접근 권한 없음",
            content = [
                Content(
                    mediaType = "application/json",
                    schema = Schema(implementation = CommonApiResponse::class),
                    examples = [
                        ExampleObject(
                            value = """{"status":"403 FORBIDDEN","code":403,"message":"요청을 수행할 권한이 없습니다."}""",
                        ),
                    ],
                ),
            ],
        ),
    )
    @PostMapping("/{templateId}/sections")
    @ResponseStatus(HttpStatus.CREATED)
    fun addSection(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable channelId: Long,
        @PathVariable templateId: Long,
        @RequestBody request: CreateTemplateSectionRequest,
    ): TemplateSectionResponse = addTemplateSectionService.addSection(userId, channelId, templateId, request)

    @Operation(summary = "섹션 수정", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(
            responseCode = "400",
            description = "요청 값이 올바르지 않거나 지원하지 않는 섹션 유형 또는 DM 채널",
            content = [
                Content(
                    mediaType = "application/json",
                    schema = Schema(implementation = CommonApiResponse::class),
                ),
            ],
        ),
        ApiResponse(
            responseCode = "409",
            description = "보관된 채널 또는 같은 제목의 섹션이 이미 있음",
            content = [
                Content(
                    mediaType = "application/json",
                    schema = Schema(implementation = CommonApiResponse::class),
                ),
            ],
        ),
        ApiResponse(
            responseCode = "404",
            description = "채널, 템플릿 또는 섹션 없음",
            content = [
                Content(
                    mediaType = "application/json",
                    schema = Schema(implementation = CommonApiResponse::class),
                ),
            ],
        ),
        ApiResponse(responseCode = "200", description = "수정 성공"),
        ApiResponse(
            responseCode = "403",
            description = "채널 접근 권한 없음",
            content = [
                Content(
                    mediaType = "application/json",
                    schema = Schema(implementation = CommonApiResponse::class),
                    examples = [
                        ExampleObject(
                            value = """{"status":"403 FORBIDDEN","code":403,"message":"요청을 수행할 권한이 없습니다."}""",
                        ),
                    ],
                ),
            ],
        ),
    )
    @PatchMapping("/{templateId}/sections/{sectionId}")
    fun updateSection(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable channelId: Long,
        @PathVariable templateId: Long,
        @PathVariable sectionId: Long,
        @RequestBody request: UpdateTemplateSectionRequest,
    ): TemplateSectionResponse =
        updateTemplateSectionService.updateSection(userId, channelId, templateId, sectionId, request)

    @Operation(summary = "섹션 삭제", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(
            responseCode = "400",
            description = "요청 값이 올바르지 않거나 DM 채널",
            content = [
                Content(
                    mediaType = "application/json",
                    schema = Schema(implementation = CommonApiResponse::class),
                ),
            ],
        ),
        ApiResponse(
            responseCode = "409",
            description = "보관된 채널은 변경할 수 없음",
            content = [
                Content(
                    mediaType = "application/json",
                    schema = Schema(implementation = CommonApiResponse::class),
                ),
            ],
        ),
        ApiResponse(
            responseCode = "404",
            description = "채널, 템플릿 또는 섹션 없음",
            content = [
                Content(
                    mediaType = "application/json",
                    schema = Schema(implementation = CommonApiResponse::class),
                ),
            ],
        ),
        ApiResponse(responseCode = "204", description = "삭제 성공", content = [Content()]),
        ApiResponse(
            responseCode = "403",
            description = "채널 접근 권한 없음",
            content = [
                Content(
                    mediaType = "application/json",
                    schema = Schema(implementation = CommonApiResponse::class),
                    examples = [
                        ExampleObject(
                            value = """{"status":"403 FORBIDDEN","code":403,"message":"요청을 수행할 권한이 없습니다."}""",
                        ),
                    ],
                ),
            ],
        ),
    )
    @DeleteMapping("/{templateId}/sections/{sectionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteSection(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable channelId: Long,
        @PathVariable templateId: Long,
        @PathVariable sectionId: Long,
    ) {
        deleteTemplateSectionService.deleteSection(userId, channelId, templateId, sectionId)
    }
}
