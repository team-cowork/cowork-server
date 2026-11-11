package com.cowork.channel.domain.meetingNote.presentation.controller

import com.cowork.channel.domain.meetingNote.presentation.data.request.CreateMeetingNoteRequest
import com.cowork.channel.domain.meetingNote.presentation.data.request.UpdateMeetingNoteRequest
import com.cowork.channel.domain.meetingNote.presentation.data.response.MeetingNoteResponse
import com.cowork.channel.domain.meetingNote.service.CreateMeetingNoteService
import com.cowork.channel.domain.meetingNote.service.DeleteMeetingNoteService
import com.cowork.channel.domain.meetingNote.service.GetMeetingNoteService
import com.cowork.channel.domain.meetingNote.service.ListMeetingNotesService
import com.cowork.channel.domain.meetingNote.service.UpdateMeetingNoteService
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

@Tag(name = "회의록")
@ApiResponses(
    ApiResponse(
        responseCode = "429",
        description = "요청 한도 초과",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = CommonApiResponse::class))],
    ),
    ApiResponse(
        responseCode = "400",
        description = "요청 값이 올바르지 않거나 DM 채널",
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
@RequestMapping("/channels/{channelId}/meeting-notes")
class MeetingNoteController(
    private val listMeetingNotesService: ListMeetingNotesService,
    private val getMeetingNoteService: GetMeetingNoteService,
    private val createMeetingNoteService: CreateMeetingNoteService,
    private val updateMeetingNoteService: UpdateMeetingNoteService,
    private val deleteMeetingNoteService: DeleteMeetingNoteService,
) {

    @Operation(summary = "회의록 목록 조회", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
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
    fun listNotes(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable channelId: Long,
    ): List<MeetingNoteResponse> = listMeetingNotesService.listNotes(userId, channelId)

    @Operation(summary = "회의록 상세 조회", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
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
        ApiResponse(
            responseCode = "404",
            description = "채널 또는 회의록 없음",
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
    @GetMapping("/{noteId}")
    fun getNote(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable channelId: Long,
        @PathVariable noteId: Long,
    ): MeetingNoteResponse = getMeetingNoteService.getNote(userId, channelId, noteId)

    @Operation(summary = "회의록 작성", security = [SecurityRequirement(name = "BearerAuth")])
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
            responseCode = "201",
            description = "작성 성공",
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
                            schema = Schema(implementation = MeetingNoteResponse::class),
                        ),
                    ],
                ),
            ],
        ),
        ApiResponse(
            responseCode = "400",
            description = "DM 채널, 잘못된 제목 또는 JSON 객체가 아닌 content",
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
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun createNote(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable channelId: Long,
        @RequestBody request: CreateMeetingNoteRequest,
    ): MeetingNoteResponse = createMeetingNoteService.createNote(userId, channelId, request)

    @Operation(summary = "회의록 수정", security = [SecurityRequirement(name = "BearerAuth")])
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
        ApiResponse(responseCode = "200", description = "수정 성공"),
        ApiResponse(
            responseCode = "400",
            description = "DM 채널, 잘못된 제목 또는 JSON 객체가 아닌 content",
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
            description = "채널 접근 권한이 없거나 작성자가 아님",
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
        ApiResponse(
            responseCode = "404",
            description = "채널 또는 회의록 없음",
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
    @PatchMapping("/{noteId}")
    fun updateNote(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable channelId: Long,
        @PathVariable noteId: Long,
        @RequestBody request: UpdateMeetingNoteRequest,
    ): MeetingNoteResponse = updateMeetingNoteService.updateNote(userId, channelId, noteId, request)

    @Operation(summary = "회의록 삭제", security = [SecurityRequirement(name = "BearerAuth")])
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
        ApiResponse(responseCode = "204", description = "삭제 성공", content = [Content()]),
        ApiResponse(
            responseCode = "403",
            description = "채널 접근 권한이 없거나 작성자가 아님",
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
        ApiResponse(
            responseCode = "404",
            description = "채널 또는 회의록 없음",
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
    @DeleteMapping("/{noteId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteNote(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable channelId: Long,
        @PathVariable noteId: Long,
    ) {
        deleteMeetingNoteService.deleteNote(userId, channelId, noteId)
    }
}
