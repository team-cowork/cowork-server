package com.cowork.project.domain.project.presentation.controller

import com.cowork.project.domain.project.presentation.data.request.CreateProjectReqDto
import com.cowork.project.domain.project.presentation.data.request.UpdateProjectReqDto
import com.cowork.project.domain.project.presentation.data.response.ProjectAccessUserResponse
import com.cowork.project.domain.project.presentation.data.response.ProjectDetailResDto
import com.cowork.project.domain.project.presentation.data.response.ProjectResDto
import com.cowork.project.domain.project.service.CreateProjectService
import com.cowork.project.domain.project.service.DeleteProjectService
import com.cowork.project.domain.project.service.QueryMyProjectsService
import com.cowork.project.domain.project.service.QueryProjectMembersService
import com.cowork.project.domain.project.service.QueryProjectService
import com.cowork.project.domain.project.service.QueryProjectsByTeamIdService
import com.cowork.project.domain.project.service.UpdateProjectService
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
import jakarta.validation.Valid
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.web.PageableDefault
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import team.themoment.sdk.response.CommonApiResponse

@Tag(name = "프로젝트", description = "프로젝트 생성/수정/삭제 및 접근자 조회 API")
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
@RequestMapping("/projects")
class ProjectController(
    private val createProjectService: CreateProjectService,
    private val queryProjectService: QueryProjectService,
    private val updateProjectService: UpdateProjectService,
    private val deleteProjectService: DeleteProjectService,
    private val queryProjectsByTeamIdService: QueryProjectsByTeamIdService,
    private val queryMyProjectsService: QueryMyProjectsService,
    private val queryProjectMembersService: QueryProjectMembersService,
) {

    @Operation(summary = "프로젝트 생성", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
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
                        SchemaProperty(name = "data", schema = Schema(implementation = ProjectResDto::class)),
                    ],
                ),
            ],
        ),
        ApiResponse(
            responseCode = "400",
            description = "프로젝트 이름이 비어 있거나 100자를 초과함",
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
            description = "권한 없음",
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
            responseCode = "409",
            description = "다른 요청과 프로젝트 순서가 충돌함. 다시 시도할 수 있습니다.",
            content = [
                Content(
                    mediaType = "application/json",
                    schema = Schema(implementation = CommonApiResponse::class),
                    examples = [
                        ExampleObject(
                            value = """{"status":"409 CONFLICT","code":409,"message":"현재 상태에서는 요청을 처리할 수 없습니다."}""",
                        ),
                    ],
                ),
            ],
        ),
        ApiResponse(
            responseCode = "503",
            description = "비공개 프로젝트의 초기 권한 정보가 아직 준비되지 않음",
            content = [
                Content(
                    mediaType = "application/json",
                    schema = Schema(implementation = CommonApiResponse::class),
                    examples = [
                        ExampleObject(
                            value = """{"status":"503 SERVICE_UNAVAILABLE","code":503,"message":"일시적으로 처리할 수 없습니다."}""",
                        ),
                    ],
                ),
            ],
        ),
    )
    @PostMapping
    fun createProject(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @Valid @RequestBody request: CreateProjectReqDto,
    ): ResponseEntity<ProjectResDto> = ResponseEntity.status(201).body(createProjectService.execute(userId, request))

    @Operation(summary = "프로젝트 상세 조회", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
        ApiResponse(
            responseCode = "403",
            description = "프로젝트 읽기 권한 없음",
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
            description = "프로젝트 없음",
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
    @GetMapping("/{projectId}")
    fun getProject(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable projectId: Long,
    ): ResponseEntity<ProjectDetailResDto> = ResponseEntity.ok(queryProjectService.execute(userId, projectId))

    @Operation(summary = "프로젝트 수정", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "수정 성공"),
        ApiResponse(
            responseCode = "400",
            description = "프로젝트 이름이 비어 있거나 100자를 초과함",
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
            description = "권한 없음",
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
            description = "프로젝트 없음",
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
    @PatchMapping("/{projectId}")
    fun updateProject(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable projectId: Long,
        @Valid @RequestBody request: UpdateProjectReqDto,
    ): ResponseEntity<ProjectResDto> = ResponseEntity.ok(updateProjectService.execute(userId, projectId, request))

    @Operation(summary = "프로젝트 삭제", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "삭제 성공", content = [Content()]),
        ApiResponse(
            responseCode = "403",
            description = "권한 없음",
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
            description = "프로젝트 없음",
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
    @DeleteMapping("/{projectId}")
    fun deleteProject(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable projectId: Long,
    ): ResponseEntity<Void> {
        deleteProjectService.execute(userId, projectId)
        return ResponseEntity.noContent().build()
    }

    @Operation(summary = "팀 프로젝트 목록 조회", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
        ApiResponse(
            responseCode = "400",
            description = "지원하지 않는 정렬 기준 (허용: position, id, name, createdAt, updatedAt)",
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
    )
    @GetMapping
    fun getProjectsByTeamId(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @RequestParam teamId: Long,
        @PageableDefault(size = 20, sort = ["position", "id"]) pageable: Pageable,
    ): ResponseEntity<Page<ProjectResDto>> =
        ResponseEntity.ok(queryProjectsByTeamIdService.execute(userId, teamId, pageable))

    @Operation(summary = "내 프로젝트 목록 조회", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
        ApiResponse(
            responseCode = "400",
            description = "지원하지 않는 정렬 기준 (허용: position, id, name, createdAt, updatedAt)",
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
    )
    @GetMapping("/me")
    fun getMyProjects(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PageableDefault(size = 20, sort = ["createdAt", "id"]) pageable: Pageable,
    ): ResponseEntity<Page<ProjectResDto>> = ResponseEntity.ok(queryMyProjectsService.execute(userId, pageable))

    @Operation(summary = "프로젝트에 접근할 수 있는 사용자 목록 조회", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
        ApiResponse(
            responseCode = "403",
            description = "프로젝트 읽기 권한 없음",
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
            description = "프로젝트 없음",
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
    @GetMapping("/{projectId}/members")
    fun getMembers(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable projectId: Long,
    ): ResponseEntity<List<ProjectAccessUserResponse>> =
        ResponseEntity.ok(queryProjectMembersService.execute(userId, projectId))
}
