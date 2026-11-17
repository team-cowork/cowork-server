package com.cowork.project.domain.githubBoard.presentation.data.response

import com.cowork.project.domain.githubBoard.entity.GithubProjectProjection
import com.cowork.project.domain.githubBoard.entity.ProjectGithubBoard
import io.swagger.v3.oas.annotations.media.Schema

enum class GithubBoardSyncStatus { SYNCING, SYNCED, NOT_FOUND, FORBIDDEN }

data class ProjectGithubBoardResDto(
    @field:Schema(description = "연결 ID", example = "1")
    val id: Long,
    @field:Schema(description = "GitHub Project를 소유한 조직", example = "my-org")
    val owner: String,
    @field:Schema(description = "GitHub Project 번호", example = "3")
    val projectNumber: Int,
    @field:Schema(description = "GitHub Project URL", example = "https://github.com/orgs/my-org/projects/3")
    val url: String,
    @field:Schema(description = "GitHub Project 제목. 동기화 전에는 null", example = "Roadmap", nullable = true)
    val title: String?,
    @field:Schema(description = "GitHub에서 닫힌 Project 여부", example = "false")
    val closed: Boolean,
    @field:Schema(
        description = "동기화 상태. SYNCING은 첫 동기화 전, NOT_FOUND는 Project 없음, FORBIDDEN은 앱에 Projects 권한 없음",
        example = "SYNCED",
    )
    val syncStatus: GithubBoardSyncStatus,
) {
    companion object {
        fun of(board: ProjectGithubBoard, projection: GithubProjectProjection?): ProjectGithubBoardResDto {
            val status = when {
                projection == null -> GithubBoardSyncStatus.SYNCING
                projection.deleted || projection.accessError == "NOT_FOUND" -> GithubBoardSyncStatus.NOT_FOUND
                projection.accessError != null -> GithubBoardSyncStatus.FORBIDDEN
                else -> GithubBoardSyncStatus.SYNCED
            }
            return ProjectGithubBoardResDto(
                id = board.id,
                owner = board.ownerLogin,
                projectNumber = board.projectNumber,
                url = projection?.url?.takeIf { it.isNotBlank() } ?: board.url,
                title = projection?.title?.takeIf { status == GithubBoardSyncStatus.SYNCED },
                closed = projection?.closed ?: false,
                syncStatus = status,
            )
        }
    }
}
