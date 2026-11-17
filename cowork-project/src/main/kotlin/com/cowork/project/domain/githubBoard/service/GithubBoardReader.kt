package com.cowork.project.domain.githubBoard.service

import com.cowork.project.domain.github.entity.GithubSyncMarker
import com.cowork.project.domain.github.entity.GithubSyncStream
import com.cowork.project.domain.github.service.GithubSyncMarkers
import com.cowork.project.domain.githubBoard.entity.GithubProjectProjection
import com.cowork.project.domain.githubBoard.entity.ProjectGithubBoard
import com.cowork.project.domain.githubBoard.event.GithubProjectField
import com.cowork.project.domain.githubBoard.event.GithubProjectItemStateEvent
import com.cowork.project.domain.githubBoard.event.GithubProjectView
import com.cowork.project.domain.githubBoard.model.GithubBoard
import com.cowork.project.domain.githubBoard.repository.GithubProjectItemProjectionRepository
import com.cowork.project.domain.githubBoard.repository.GithubProjectProjectionRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import team.themoment.sdk.exception.ExpectedException
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper

/**
 * Projection에서 보드 하나를 읽는다. 동기화 완료 마커가 없으면 `503`으로 기다리게 하고, GitHub에서 Project를 찾을 수 없거나
 * 앱에 Projects 권한이 없으면 그 원인으로 응답한다. item은 Project 레코드의 순서 목록에 있는 것만 그 순서로 돌려준다.
 */
@Component
class GithubBoardReader(
    private val projectRepository: GithubProjectProjectionRepository,
    private val itemRepository: GithubProjectItemProjectionRepository,
    private val syncMarkers: GithubSyncMarkers,
    private val objectMapper: ObjectMapper,
) {
    fun read(board: ProjectGithubBoard): GithubBoard {
        val scope = GithubSyncMarker.projectScope(board.projectNumber)
        syncMarkers.requireSynced(GithubSyncStream.PROJECT, board.ownerLogin, scope)
        syncMarkers.requireSynced(GithubSyncStream.PROJECT_ITEM, board.ownerLogin, scope)
        val projection = projectRepository.findById(board.projectionId).orElseThrow {
            ExpectedException("GitHub 데이터 동기화가 완료되지 않았습니다.", HttpStatus.SERVICE_UNAVAILABLE)
        }
        requireAvailable(projection)
        val nodeId = requireNotNull(projection.nodeId)
        val order = objectMapper.readValue(projection.itemIdsJson, STRING_LIST)
        val itemsById = itemRepository.findAllByProjectNodeIdAndDeletedFalse(nodeId)
            .filterNot { it.archived }
            .associate { it.id to it.payloadJson }
        return GithubBoard(
            projectNodeId = nodeId,
            fields = objectMapper.readValue(projection.fieldsJson, FIELD_LIST),
            views = objectMapper.readValue(projection.viewsJson, VIEW_LIST),
            items = order.mapNotNull { id ->
                itemsById[id]?.let { objectMapper.readValue(it, GithubProjectItemStateEvent::class.java) }
            },
        )
    }

    private fun requireAvailable(projection: GithubProjectProjection) {
        val missing = projection.deleted || projection.accessError == ACCESS_NOT_FOUND || projection.nodeId == null
        when {
            missing -> throw ExpectedException(
                "GitHub Project를 찾을 수 없습니다. 조직 소유 Project인지 확인해 주십시오.",
                HttpStatus.NOT_FOUND,
            )

            projection.accessError != null -> throw ExpectedException(
                "GitHub App에 Projects 권한이 없습니다. 조직 관리자가 권한 변경을 승인해야 합니다.",
                HttpStatus.CONFLICT,
            )
        }
    }

    private companion object {
        const val ACCESS_NOT_FOUND = "NOT_FOUND"
        val STRING_LIST = object : TypeReference<List<String>>() {}
        val FIELD_LIST = object : TypeReference<List<GithubProjectField>>() {}
        val VIEW_LIST = object : TypeReference<List<GithubProjectView>>() {}
    }
}
