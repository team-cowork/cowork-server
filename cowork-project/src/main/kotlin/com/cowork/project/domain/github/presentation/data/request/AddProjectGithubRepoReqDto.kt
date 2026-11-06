package com.cowork.project.domain.github.presentation.data.request

import io.swagger.v3.oas.annotations.media.Schema

data class AddProjectGithubRepoReqDto(
    @param:Schema(description = "등록할 GitHub 저장소 URL", example = "https://github.com/my-org/my-repo", required = true)
    val githubRepoUrl: String,
)
