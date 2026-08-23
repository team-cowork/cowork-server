package com.cowork.project.domain.github.presentation.data.request

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "레포별 라벨 자동/수동 적용 정책 변경 요청")
data class UpdateGithubLabelPolicyReqDto(
    @field:Schema(description = "true면 GitHub App이 판단한 라벨을 자동 적용, false면 사람이 직접 골라 적용", example = "false")
    val autoApply: Boolean,
)
