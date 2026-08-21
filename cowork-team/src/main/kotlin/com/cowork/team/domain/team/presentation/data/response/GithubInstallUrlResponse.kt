package com.cowork.team.domain.team.presentation.data.response

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "GitHub App 설치 URL 발급 응답")
data class GithubInstallUrlResponse(
    @field:Schema(description = "GitHub App 설치 페이지로 리다이렉트할 URL") val installUrl: String,
)
