package com.cowork.project.domain.project.presentation.data.response

import com.cowork.project.domain.project.entity.Project
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

data class ProjectDetailResDto(
    @Schema(description = "프로젝트 ID", example = "1")
    val id: Long,
    @Schema(description = "팀 ID", example = "1")
    val teamId: Long,
    @Schema(description = "프로젝트 이름", example = "코워크 앱 개발")
    val name: String,
    @Schema(description = "프로젝트 설명", example = "모바일 앱 개발 프로젝트")
    val description: String?,
    @Schema(description = "프로젝트 상태", example = "ACTIVE", allowableValues = ["ACTIVE", "ARCHIVED"])
    val status: String,
    @Schema(description = "팀 내 프로젝트 정렬 순서", example = "0")
    val position: Int,
    @Schema(description = "생성자 사용자 ID", example = "1")
    val createdBy: Long,
    @Schema(description = "생성 일시")
    val createdAt: LocalDateTime,
    @Schema(description = "수정 일시")
    val updatedAt: LocalDateTime,
    @Schema(description = "프로젝트 멤버 수", example = "5")
    val memberCount: Long,
    @field:Schema(description = "연결된 GitHub 레포지토리 URL", example = "https://github.com/my-org/my-repo")
    val githubRepoUrl: String?,
) {
    companion object {
        fun of(project: Project, memberCount: Long): ProjectDetailResDto =
            ProjectDetailResDto(
                id = project.id,
                teamId = project.teamId,
                name = project.name,
                description = project.description,
                status = project.status.name,
                position = project.position,
                createdBy = project.createdBy,
                createdAt = project.createdAt,
                updatedAt = project.updatedAt,
                memberCount = memberCount,
                githubRepoUrl = project.githubRepoUrl,
            )
    }
}
