package com.cowork.project.domain.github.service

import com.cowork.project.global.projection.ProjectionCheckpointStore
import com.cowork.project.global.projection.ProjectionStreams
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import team.themoment.sdk.exception.ExpectedException

@Component
class GithubTimelineReadiness(
    private val projectionCheckpointStore: ProjectionCheckpointStore,
    private val projectionStreams: ProjectionStreams,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Volatile
    private var ready = false

    // 토픽의 세대와 현재 끝 오프셋을 확인한다. 각 대상의 완료 여부는 조회 트랜잭션에서 별도로 검사한다.
    @Scheduled(fixedDelayString = "\${projection.readiness.poll-interval-ms:1000}")
    fun refresh() {
        ready = runCatching { projectionCheckpointStore.isCaughtUp(setOf(projectionStreams.githubTimelineState)) }
            .onFailure { if (ready) log.warn("Failed to check GitHub timeline consumer progress", it) }
            .getOrDefault(false)
    }

    fun requireReady() {
        if (!ready) {
            throw ExpectedException("활동 이력을 동기화하고 있습니다. 잠시 후 다시 조회하십시오.", HttpStatus.SERVICE_UNAVAILABLE)
        }
    }
}
