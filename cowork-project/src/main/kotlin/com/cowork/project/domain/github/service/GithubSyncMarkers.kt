package com.cowork.project.domain.github.service

import com.cowork.project.domain.github.entity.GithubSyncMarker
import com.cowork.project.domain.github.entity.GithubSyncStream
import com.cowork.project.domain.github.event.GithubSyncCompletedMarker
import com.cowork.project.domain.github.repository.GithubSyncMarkerRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import team.themoment.sdk.exception.ExpectedException

/**
 * state 토픽의 동기화 완료 마커를 기록하고, reader가 projection을 믿어도 되는지 판단한다.
 * 마커가 없으면 그 대상은 아직 동기화 전이거나 동기화가 끝나지 않았으므로 `503`으로 응답한다.
 */
@Component
class GithubSyncMarkers(
    private val repository: GithubSyncMarkerRepository,
    private val objectMapper: ObjectMapper,
) {
    /** state 토픽 레코드가 완료 마커면 기록하고 `true`를 돌려준다. consumer 트랜잭션 안에서 호출한다. */
    fun recordIfMarker(stream: GithubSyncStream, json: String): Boolean {
        val node = objectMapper.readTree(json)
        if (node.path("recordType").asText() != SYNC_COMPLETED) return false

        val marker = objectMapper.treeToValue(node, GithubSyncCompletedMarker::class.java)
        require(marker.owner.isNotBlank()) { "동기화 완료 마커에 owner가 없습니다." }
        val id = GithubSyncMarker.id(stream, marker.owner, marker.repo)
        val existing = repository.findById(id).orElse(null)
        when {
            existing == null -> repository.save(GithubSyncMarker(id, marker.completedAt))
            marker.completedAt.isAfter(existing.completedAt) -> existing.completedAt = marker.completedAt
        }
        return true
    }

    /** 설치 범위에서 빠지거나 삭제된 repo의 projection을 더 이상 완전한 목록으로 믿지 않도록 repo 단위 마커를 지운다. */
    fun clearRepo(owner: String, repo: String) {
        repository.deleteAllById(GithubSyncTarget(owner, repo, null).markerIds)
    }

    fun requireSynced(stream: GithubSyncStream, owner: String, repo: String? = null) {
        if (!repository.existsById(GithubSyncMarker.id(stream, owner, repo))) {
            throw ExpectedException("GitHub 데이터 동기화가 완료되지 않았습니다.", HttpStatus.SERVICE_UNAVAILABLE)
        }
    }

    private companion object {
        const val SYNC_COMPLETED = "SYNC_COMPLETED"
    }
}
