package com.cowork.project.domain.github.presentation.data.response

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "팀 GitHub 조직의 저장소 요약")
data class GithubRepoSummaryResDto(
    @field:Schema(description = "저장소 이름", example = "my-repo")
    val name: String,
    @field:Schema(description = "owner/repo 형식의 전체 이름", example = "my-org/my-repo")
    val fullName: String,
    @field:Schema(description = "비공개 저장소 여부")
    val private: Boolean,
    @field:Schema(description = "GitHub 저장소 URL", example = "https://github.com/my-org/my-repo")
    val htmlUrl: String,
)
