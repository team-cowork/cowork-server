package com.cowork.project.dto

import com.cowork.project.domain.Project
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

data class ProjectResponse(
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
    @Schema(description = "생성자 사용자 ID", example = "1")
    val createdBy: Long,
    @Schema(description = "생성 일시")
    val createdAt: LocalDateTime,
    @Schema(description = "수정 일시")
    val updatedAt: LocalDateTime,
) {
    companion object {
        fun of(project: Project): ProjectResponse =
            ProjectResponse(
                id = project.id,
                teamId = project.teamId,
                name = project.name,
                description = project.description,
                status = project.status.name,
                createdBy = project.createdBy,
                createdAt = project.createdAt,
                updatedAt = project.updatedAt,
            )
    }
}
