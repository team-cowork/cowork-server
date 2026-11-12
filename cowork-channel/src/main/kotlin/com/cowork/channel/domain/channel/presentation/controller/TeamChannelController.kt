package com.cowork.channel.domain.channel.presentation.controller

import com.cowork.channel.domain.channel.presentation.data.request.ReorderChannelsRequest
import com.cowork.channel.domain.channel.presentation.data.response.ArchivedChannelPageResponse
import com.cowork.channel.domain.channel.presentation.data.response.ChannelResponse
import com.cowork.channel.domain.channel.service.ListTeamChannelsService
import com.cowork.channel.domain.channel.service.ReorderTeamChannelsService
import com.cowork.channel.domain.channel.service.SearchArchivedChannelsService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import team.themoment.sdk.response.CommonApiResponse

@Tag(name = "팀 채널", description = "팀에 속한 채널의 목록과 순서를 관리합니다.")
@ApiResponses(
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
@RequestMapping("/teams")
class TeamChannelController(
    private val listTeamChannelsService: ListTeamChannelsService,
    private val reorderTeamChannelsService: ReorderTeamChannelsService,
    private val searchArchivedChannelsService: SearchArchivedChannelsService,
) {

    @Operation(summary = "팀 채널 목록 조회", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
        ApiResponse(
            responseCode = "403",
            description = "해당 팀의 멤버가 아님",
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
    @GetMapping("/{teamId}/channels")
    fun listTeamChannels(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable teamId: Long,
    ): List<ChannelResponse> = listTeamChannelsService.execute(userId, teamId).map(ChannelResponse::of)

    @Operation(
        summary = "팀 채널 순서 변경",
        description = "조회 가능한 모든 채널 ID를 전달합니다. 위치가 바뀌는 채널마다 관리 또는 channel_reorder 권한이 필요합니다. 숨겨진 채널의 위치는 유지합니다.",
        security = [SecurityRequirement(name = "BearerAuth")],
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "변경 성공"),
        ApiResponse(
            responseCode = "400",
            description = "유효하지 않은 채널 순서",
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
            description = "팀 멤버가 아니거나 재정렬 권한이 없음",
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
    @PatchMapping("/{teamId}/channels/reorder")
    fun reorderTeamChannels(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable teamId: Long,
        @RequestBody request: ReorderChannelsRequest,
    ): List<ChannelResponse> = reorderTeamChannelsService.execute(userId, teamId, request.orderedChannelIds)

    @Operation(
        summary = "보관된 채널 조회",
        description = "읽을 수 있는 보관 채널을 보관 시각의 역순으로 조회합니다. 채널 ID, 프로젝트 ID, 채널 이름으로 좁힐 수 있습니다. " +
            "nextCursor가 null이 될 때까지 cursor로 다음 페이지를 조회합니다. " +
            "보관 채널의 메시지 내용 검색은 채팅 서비스의 메시지 검색 API에 archived=true를 지정합니다.",
        security = [SecurityRequirement(name = "BearerAuth")],
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
        ApiResponse(
            responseCode = "400",
            description = "조회 크기 또는 커서 형식 오류",
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
            description = "해당 팀의 멤버가 아님",
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
    @GetMapping("/{teamId}/channels/archived")
    fun searchArchivedChannels(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable teamId: Long,
        @Parameter(description = "채널 ID") @RequestParam(required = false) channelId: Long?,
        @Parameter(description = "프로젝트 ID") @RequestParam(required = false) projectId: Long?,
        @Parameter(description = "채널 이름 검색어") @RequestParam(required = false) q: String?,
        @Parameter(description = "이전 응답의 nextCursor") @RequestParam(required = false) cursor: String?,
        @Parameter(description = "한 번에 조회할 보관 채널 수 (1~100)") @RequestParam(defaultValue = "50") size: Int,
    ): ArchivedChannelPageResponse =
        searchArchivedChannelsService.execute(userId, teamId, channelId, projectId, q, cursor, size)
}
