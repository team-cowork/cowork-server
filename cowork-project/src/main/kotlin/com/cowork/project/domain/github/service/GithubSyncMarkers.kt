package com.cowork.project.domain.github.service

import com.cowork.project.domain.github.entity.GithubRepoProjection
import com.cowork.project.domain.github.entity.GithubSyncMarker
import com.cowork.project.domain.github.entity.GithubSyncStream
import com.cowork.project.domain.github.event.GithubSyncCompletedMarker
import com.cowork.project.domain.github.repository.GithubRepoProjectionRepository
import com.cowork.project.domain.github.repository.GithubSyncMarkerRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import team.themoment.sdk.exception.ExpectedException
import tools.jackson.databind.JsonNode
import java.util.Locale

/**
 * state 토픽의 동기화 완료 마커를 기록하고, reader가 projection을 믿어도 되는지 판단한다.
 * 마커가 없으면 그 대상은 아직 동기화 전이거나 동기화가 끝나지 않았으므로 `503`으로 응답한다.
 * 다만 설치 범위에서 빠지거나 삭제된 repo는 다시 동기화되지 않으므로 `404`로 구분한다.
 */
@Component
class GithubSyncMarkers(
    private val repository: GithubSyncMarkerRepository,
    private val repoProjectionRepository: GithubRepoProjectionRepository,
) {
    fun isMarker(node: JsonNode): Boolean = node.path("recordType").asString() == SYNC_COMPLETED

    /** 완료 마커를 기록한다. 같은 대상의 더 오래된 마커는 완료 시각을 되돌리지 않는다. consumer 트랜잭션 안에서 호출한다. */
    fun record(stream: GithubSyncStream, marker: GithubSyncCompletedMarker) {
        val id = GithubSyncMarker.id(stream, marker.owner, marker.repo)
        val existing = repository.findById(id).orElse(null)
        when {
            existing == null -> repository.save(GithubSyncMarker(id, marker.completedAt))
            marker.completedAt.isAfter(existing.completedAt) -> existing.completedAt = marker.completedAt
        }
    }

    /** 설치 범위에서 빠지거나 삭제된 repo의 projection을 더 이상 완전한 목록으로 믿지 않도록 repo 단위 마커를 지운다. */
    fun clearRepo(owner: String, repo: String) {
        repository.deleteAllById(GithubSyncTarget(owner, repo, null).markerIds)
    }

    /**
     * 격리한 state 레코드가 속한 대상의 마커를 지워, 빠진 상태를 `503`으로 드러내고 스케줄러가 다시 동기화하게 한다.
     * 대상을 알 수 없으면 그 토픽의 마커를 모두 지운다.
     */
    fun invalidate(stream: GithubSyncStream, owner: String?, repo: String?) {
        if (owner == null) {
            repository.deleteByIdStartingWith("$stream:")
            return
        }
        val ownerMarkerId = GithubSyncMarker.id(stream, owner, null)
        when {
            stream == GithubSyncStream.REPO -> repository.deleteById(ownerMarkerId)
            repo == null -> repository.deleteByIdStartingWith("$ownerMarkerId/")
            else -> repository.deleteById(GithubSyncMarker.id(stream, owner, repo))
        }
    }

    fun requireSynced(stream: GithubSyncStream, owner: String, repo: String? = null) {
        if (repository.existsById(GithubSyncMarker.id(stream, owner, repo))) return
        if (repo != null && isRemovedRepo(owner, repo)) {
            throw ExpectedException("GitHub에서 삭제되었거나 앱 설치 범위에서 빠진 레포입니다.", HttpStatus.NOT_FOUND)
        }
        throw ExpectedException("GitHub 데이터 동기화가 완료되지 않았습니다.", HttpStatus.SERVICE_UNAVAILABLE)
    }

    private fun isRemovedRepo(owner: String, repo: String): Boolean {
        val id = GithubRepoProjection.id(owner.lowercase(Locale.ROOT), repo.lowercase(Locale.ROOT))
        return repoProjectionRepository.findById(id).map { it.deleted }.orElse(false)
    }

    private companion object {
        const val SYNC_COMPLETED = "SYNC_COMPLETED"
    }
}
