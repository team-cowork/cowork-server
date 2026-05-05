package com.cowork.project.dto

import com.cowork.project.domain.ProjectMember
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

data class ProjectMemberResponse(
    @Schema(description = "멤버 레코드 ID", example = "1")
    val id: Long,
    @Schema(description = "프로젝트 ID", example = "1")
    val projectId: Long,
    @Schema(description = "사용자 ID", example = "42")
    val userId: Long,
    @Schema(description = "역할", example = "EDITOR", allowableValues = ["OWNER", "EDITOR", "VIEWER"])
    val role: String,
    @Schema(description = "참여 일시")
    val joinedAt: LocalDateTime,
) {
    companion object {
        fun of(member: ProjectMember): ProjectMemberResponse =
            ProjectMemberResponse(
                id = member.id,
                projectId = member.projectId,
                userId = member.userId,
                role = member.role.name,
                joinedAt = member.joinedAt,
            )
    }
}
