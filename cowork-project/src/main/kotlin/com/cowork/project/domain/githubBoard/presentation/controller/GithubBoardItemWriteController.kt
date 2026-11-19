package com.cowork.project.domain.githubBoard.presentation.controller

import com.cowork.project.domain.githubBoard.presentation.data.request.AddGithubBoardItemReqDto
import com.cowork.project.domain.githubBoard.presentation.data.request.ConvertGithubBoardDraftReqDto
import com.cowork.project.domain.githubBoard.presentation.data.request.CreateGithubBoardDraftReqDto
import com.cowork.project.domain.githubBoard.presentation.data.request.SetGithubBoardAssigneesReqDto
import com.cowork.project.domain.githubBoard.presentation.data.request.SetGithubBoardFieldReqDto
import com.cowork.project.domain.githubBoard.presentation.data.request.UpdateGithubBoardDraftReqDto
import com.cowork.project.domain.githubBoard.presentation.data.response.GithubBoardOperationAcceptedResDto
import com.cowork.project.domain.githubBoard.service.WriteGithubBoardItemService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.links.Link
import io.swagger.v3.oas.annotations.links.LinkParameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.media.SchemaProperty
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import team.themoment.sdk.response.CommonApiResponse
import io.swagger.v3.oas.annotations.parameters.RequestBody as SwaggerRequestBody

@Tag(name = "GitHub 보드")
@ApiResponses(
    ApiResponse(
        responseCode = "202",
        description = "작업을 접수했습니다.",
        content = [
            Content(
                schema = Schema(allOf = [CommonApiResponse::class]),
                schemaProperties = [
                    SchemaProperty(
                        name = "data",
                        schema = Schema(implementation = GithubBoardOperationAcceptedResDto::class),
                    ),
                ],
            ),
        ],
        links = [
            Link(
                name = "작업 결과 조회",
                operationId = "getGithubBoardOperation",
                parameters = [
                    LinkParameter(name = "projectId", expression = "\$request.path.projectId"),
                    LinkParameter(name = "boardId", expression = "\$request.path.boardId"),
                    LinkParameter(name = "operationId", expression = "\$response.body#/data/operationId"),
                ],
            ),
        ],
    ),
    ApiResponse(responseCode = "400", description = "요청 값이 올바르지 않습니다."),
    ApiResponse(responseCode = "401", description = "인증이 필요합니다."),
    ApiResponse(responseCode = "403", description = "보드 수정 권한이 없거나 허용된 저장소가 아닙니다."),
    ApiResponse(responseCode = "404", description = "요청한 리소스를 찾을 수 없습니다."),
    ApiResponse(responseCode = "409", description = "GitHub App의 Projects 권한이 필요합니다."),
    ApiResponse(responseCode = "503", description = "GitHub 데이터 동기화가 완료되지 않았습니다."),
)
@RestController
@RequestMapping("/projects/{projectId}/github-boards/{boardId}")
class GithubBoardItemWriteController(private val service: WriteGithubBoardItemService) {

    @Operation(
        summary = "카드 커스텀 필드 값 변경",
        security = [SecurityRequirement(name = "BearerAuth")],
        requestBody = SwaggerRequestBody(
            required = true,
            content = [
                Content(
                    schema = Schema(implementation = SetGithubBoardFieldReqDto::class),
                    examples = [
                        ExampleObject(name = "텍스트", value = """{"text":"배포 준비"}"""),
                        ExampleObject(name = "숫자", value = """{"number":3.5}"""),
                        ExampleObject(name = "날짜", value = """{"date":"2026-11-19"}"""),
                        ExampleObject(name = "선택 항목", value = """{"singleSelectOptionId":"98236657"}"""),
                        ExampleObject(name = "반복 주기", value = """{"iterationId":"cfc16e4d"}"""),
                    ],
                ),
            ],
        ),
    )
    @PutMapping("/items/{itemId}/fields/{fieldId}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun setField(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable projectId: Long,
        @PathVariable boardId: Long,
        @PathVariable itemId: String,
        @PathVariable fieldId: String,
        @Valid @RequestBody request: SetGithubBoardFieldReqDto,
    ): GithubBoardOperationAcceptedResDto = service.setField(userId, projectId, boardId, itemId, fieldId, request)

    @Operation(summary = "카드 커스텀 필드 값 비우기", security = [SecurityRequirement(name = "BearerAuth")])
    @DeleteMapping("/items/{itemId}/fields/{fieldId}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun clearField(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable projectId: Long,
        @PathVariable boardId: Long,
        @PathVariable itemId: String,
        @PathVariable fieldId: String,
    ): GithubBoardOperationAcceptedResDto = service.clearField(userId, projectId, boardId, itemId, fieldId)

    @Operation(summary = "Draft 카드 생성", security = [SecurityRequirement(name = "BearerAuth")])
    @PostMapping("/drafts")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun createDraft(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable projectId: Long,
        @PathVariable boardId: Long,
        @Valid @RequestBody request: CreateGithubBoardDraftReqDto,
    ): GithubBoardOperationAcceptedResDto = service.createDraft(userId, projectId, boardId, request)

    @Operation(
        summary = "Draft 카드 제목과 본문 수정",
        security = [SecurityRequirement(name = "BearerAuth")],
        requestBody = SwaggerRequestBody(
            required = true,
            content = [
                Content(
                    schema = Schema(implementation = UpdateGithubBoardDraftReqDto::class),
                    examples = [
                        ExampleObject(name = "제목 변경", value = """{"title":"배포 완료"}"""),
                        ExampleObject(name = "본문 변경", value = """{"body":"배포를 완료했습니다."}"""),
                        ExampleObject(name = "본문 비우기", value = """{"body":""}"""),
                    ],
                ),
            ],
        ),
    )
    @PatchMapping("/items/{itemId}/draft")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun updateDraft(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable projectId: Long,
        @PathVariable boardId: Long,
        @PathVariable itemId: String,
        @Valid @RequestBody request: UpdateGithubBoardDraftReqDto,
    ): GithubBoardOperationAcceptedResDto = service.updateDraft(userId, projectId, boardId, itemId, request)

    @Operation(summary = "연결된 저장소의 기존 이슈 또는 PR 추가", security = [SecurityRequirement(name = "BearerAuth")])
    @PostMapping("/items")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun addItem(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable projectId: Long,
        @PathVariable boardId: Long,
        @Valid @RequestBody request: AddGithubBoardItemReqDto,
    ): GithubBoardOperationAcceptedResDto = service.addItem(userId, projectId, boardId, request)

    @Operation(
        summary = "카드 제거",
        description = "Draft 카드는 삭제합니다. 연결된 이슈와 PR은 보드에서만 제거합니다.",
        security = [SecurityRequirement(name = "BearerAuth")],
    )
    @DeleteMapping("/items/{itemId}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun removeItem(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable projectId: Long,
        @PathVariable boardId: Long,
        @PathVariable itemId: String,
    ): GithubBoardOperationAcceptedResDto = service.removeItem(userId, projectId, boardId, itemId)

    @Operation(summary = "Draft 카드의 이슈 전환", security = [SecurityRequirement(name = "BearerAuth")])
    @PostMapping("/items/{itemId}/convert")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun convertDraft(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable projectId: Long,
        @PathVariable boardId: Long,
        @PathVariable itemId: String,
        @Valid @RequestBody request: ConvertGithubBoardDraftReqDto,
    ): GithubBoardOperationAcceptedResDto = service.convertDraft(userId, projectId, boardId, itemId, request)

    @Operation(
        summary = "카드에 연결된 이슈의 담당자 변경",
        security = [SecurityRequirement(name = "BearerAuth")],
        requestBody = SwaggerRequestBody(
            required = true,
            content = [
                Content(
                    schema = Schema(implementation = SetGithubBoardAssigneesReqDto::class),
                    examples = [
                        ExampleObject(name = "담당자 교체", value = """{"assignees":["octocat"]}"""),
                        ExampleObject(name = "모든 담당자 해제", value = """{"assignees":[]}"""),
                    ],
                ),
            ],
        ),
    )
    @PutMapping("/items/{itemId}/assignees")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun setAssignees(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable projectId: Long,
        @PathVariable boardId: Long,
        @PathVariable itemId: String,
        @Valid @RequestBody request: SetGithubBoardAssigneesReqDto,
    ): GithubBoardOperationAcceptedResDto = service.setAssignees(userId, projectId, boardId, itemId, request)
}
