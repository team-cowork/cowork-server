package com.cowork.project.domain.githubBoard.presentation.data.request

import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size

@Schema(description = "필드 타입에 맞는 값을 하나만 지정합니다.")
data class SetGithubBoardFieldReqDto(
    @param:Schema(title = "텍스트 값", example = "배포 준비", nullable = true)
    val text: String? = null,
    @param:Schema(title = "숫자 값", format = "double", example = "3.5", nullable = true)
    val number: Double? = null,
    @param:Schema(title = "날짜 값", format = "date", example = "2026-11-19", nullable = true)
    val date: String? = null,
    @param:Schema(title = "선택 항목 ID", minLength = 1, example = "98236657", nullable = true)
    val singleSelectOptionId: String? = null,
    @param:Schema(title = "반복 주기 ID", minLength = 1, example = "cfc16e4d", nullable = true)
    val iterationId: String? = null,
)

data class CreateGithubBoardDraftReqDto(
    @field:NotBlank
    @field:Size(max = 256)
    @param:Schema(title = "Draft 제목", maxLength = 256, example = "배포 준비")
    val title: String,
    @field:Size(max = 65536)
    @param:Schema(title = "Draft 본문", maxLength = 65536, example = "배포 항목을 확인합니다.")
    val body: String = "",
)

@Schema(description = "제목과 본문 중 하나 이상을 지정합니다. 생략하거나 null을 지정한 값은 유지합니다.")
data class UpdateGithubBoardDraftReqDto(
    @field:Size(max = 256)
    @param:Schema(title = "Draft 제목", maxLength = 256, example = "배포 완료", nullable = true)
    val title: String? = null,
    @field:Size(max = 65536)
    @param:Schema(title = "Draft 본문", maxLength = 65536, nullable = true)
    val body: String? = null,
)

data class AddGithubBoardItemReqDto(
    @field:Positive
    @param:Schema(title = "저장소 연결 ID", minimum = "1", example = "5")
    val repoId: Long,
    @field:Positive
    @param:Schema(title = "이슈 또는 PR 번호", minimum = "1", example = "42")
    val number: Int,
)

data class ConvertGithubBoardDraftReqDto(
    @field:Positive
    @param:Schema(title = "대상 저장소 연결 ID", minimum = "1", example = "5")
    val repoId: Long,
)

data class SetGithubBoardAssigneesReqDto(
    @field:Size(max = 10)
    @param:ArraySchema(
        arraySchema = Schema(
            title = "담당자 GitHub 사용자명",
            description = "사용자명은 대소문자를 구분하지 않습니다.",
            example = "[\"octocat\"]",
        ),
        schema = Schema(type = "string", minLength = 1, pattern = "^\\S+$"),
        maxItems = 10,
        uniqueItems = true,
    )
    val assignees: List<String?>,
)
