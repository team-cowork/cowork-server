package com.cowork.channel.domain.sharedAccount.presentation.controller

import com.cowork.channel.domain.sharedAccount.presentation.data.request.CreateSharedAccountRequest
import com.cowork.channel.domain.sharedAccount.presentation.data.request.UpdateSharedAccountRequest
import com.cowork.channel.domain.sharedAccount.presentation.data.response.SharedAccountResponse
import com.cowork.channel.domain.sharedAccount.service.CopySharedAccountCredentialService
import com.cowork.channel.domain.sharedAccount.service.CreateSharedAccountService
import com.cowork.channel.domain.sharedAccount.service.DeleteSharedAccountService
import com.cowork.channel.domain.sharedAccount.service.GetSharedAccountService
import com.cowork.channel.domain.sharedAccount.service.ListSharedAccountsService
import com.cowork.channel.domain.sharedAccount.service.UpdateSharedAccountService
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

@Tag(name = "계정 공유", description = "ACCOUNT_SHARE 채널의 공유 계정 관리 API")
@ApiResponses(
    ApiResponse(
        responseCode = "400",
        description = "요청 값이 올바르지 않음",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = CommonApiResponse::class))],
    ),
    ApiResponse(
        responseCode = "503",
        description = "권한 정보 동기화 중",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = CommonApiResponse::class))],
    ),
    ApiResponse(
        responseCode = "429",
        description = "요청 한도 초과",
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
@RequestMapping("/channels/{channelId}/accounts")
class SharedAccountController(
    private val listSharedAccountsService: ListSharedAccountsService,
    private val getSharedAccountService: GetSharedAccountService,
    private val createSharedAccountService: CreateSharedAccountService,
    private val updateSharedAccountService: UpdateSharedAccountService,
    private val deleteSharedAccountService: DeleteSharedAccountService,
    private val copySharedAccountCredentialService: CopySharedAccountCredentialService,
) {

    @Operation(summary = "공유 계정 목록 조회", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
        ApiResponse(
            responseCode = "400",
            description = "ACCOUNT_SHARE 채널이 아님",
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
            description = "팀 멤버가 아님",
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
            description = "채널 없음",
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
    @GetMapping
    fun listAccounts(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable channelId: Long,
    ): List<SharedAccountResponse> = listSharedAccountsService.listAccounts(userId, channelId)

    @Operation(summary = "공유 계정 상세 조회", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
        ApiResponse(
            responseCode = "403",
            description = "팀 멤버가 아님",
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
            description = "채널 또는 계정 없음",
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
    @GetMapping("/{accountId}")
    fun getAccount(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable channelId: Long,
        @PathVariable accountId: Long,
    ): SharedAccountResponse = getSharedAccountService.getAccount(userId, channelId, accountId)

    @Operation(summary = "공유 계정 등록 (수동)", security = [SecurityRequirement(name = "BearerAuth")])
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
        ApiResponse(
            responseCode = "409",
            description = "보관된 채널 또는 이미 등록된 공유 계정",
            content = [
                Content(
                    mediaType = "application/json",
                    schema = Schema(implementation = CommonApiResponse::class),
                ),
            ],
        ),
        ApiResponse(
            responseCode = "201",
            description = "등록 성공",
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
                            schema = Schema(implementation = SharedAccountResponse::class),
                        ),
                    ],
                ),
            ],
        ),
        ApiResponse(
            responseCode = "400",
            description = "ACCOUNT_SHARE 채널이 아님 또는 CUSTOM 서비스에 이름 누락",
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
            description = "팀 멤버가 아님",
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
    fun createAccount(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable channelId: Long,
        @RequestBody request: CreateSharedAccountRequest,
    ): SharedAccountResponse = createSharedAccountService.createAccount(userId, channelId, request)

    @Operation(summary = "공유 계정 수정", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(
            responseCode = "409",
            description = "보관된 채널 또는 이미 등록된 공유 계정",
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
            description = "수정 권한 없음 (등록자, 채널 생성자 또는 팀 관리자만 가능)",
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
            description = "채널 또는 계정 없음",
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
    @PatchMapping("/{accountId}")
    fun updateAccount(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable channelId: Long,
        @PathVariable accountId: Long,
        @RequestBody request: UpdateSharedAccountRequest,
    ): SharedAccountResponse = updateSharedAccountService.updateAccount(userId, channelId, accountId, request)

    @Operation(summary = "공유 계정 삭제", security = [SecurityRequirement(name = "BearerAuth")])
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
            description = "삭제 권한 없음 (등록자, 채널 생성자 또는 팀 관리자만 가능)",
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
            description = "채널 또는 계정 없음",
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
    @DeleteMapping("/{accountId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteAccount(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable channelId: Long,
        @PathVariable accountId: Long,
    ) {
        deleteSharedAccountService.deleteAccount(userId, channelId, accountId)
    }

    @Operation(
        summary = "공유 계정 인증 정보 복사",
        description = "인증 정보를 복호화해 반환하고 접근 기록을 남깁니다.",
        security = [SecurityRequirement(name = "BearerAuth")],
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "복호화한 인증 정보 반환"),
        ApiResponse(
            responseCode = "403",
            description = "팀 멤버가 아님",
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
            description = "채널, 계정 또는 인증 정보 없음",
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
    @PostMapping("/{accountId}/credential/copy")
    fun copyCredential(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable channelId: Long,
        @PathVariable accountId: Long,
    ): Map<String, String> {
        val credential = copySharedAccountCredentialService.copyCredential(userId, channelId, accountId)
        return mapOf("credential" to credential)
    }
}
