package com.cowork.project.dto

import io.swagger.v3.oas.annotations.media.Schema

data class LinkGithubRepoReqDto(
    @param:Schema(description = "연결할 GitHub 레포지토리 URL", example = "https://github.com/my-org/my-repo", required = true)
    val githubRepoUrl: String,
)
