package com.cowork.project.domain.project.presentation.data.response

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "GitHub 이슈 라벨 자동/수동 정책 조회 대상 (내부 서비스용)")
data class ProjectGithubLabelPolicyTargetResDto(
    @field:Schema(description = "팀 ID", example = "1")
    val teamId: Long,
    @field:Schema(description = "프로젝트 ID", example = "1")
    val projectId: Long,
    @field:Schema(description = "레포 연결 ID", example = "5")
    val repoId: Long,
    @field:Schema(description = "true면 GitHub App이 판단한 라벨을 자동 적용, false면 사람이 직접 골라 적용", example = "true")
    val autoApply: Boolean,
)
